package com.neobank.account.service;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.GlAccount;
import com.neobank.account.domain.GlAccountType;
import com.neobank.account.domain.JournalEntry;
import com.neobank.account.domain.PostingDirection;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.GlAccountRepository;
import com.neobank.account.repository.JournalEntryRepository;
import com.neobank.account.repository.PostingRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bank's internal integrity check: a trial balance plus the cross-checks that prove the books are sound.
 *
 *  1. TRIAL BALANCE   per currency, total debits == total credits across every posting.
 *  2. BALANCED JOURNALS no individual journal is out of balance (the DB trigger should make this impossible).
 *  3. ACCOUNT DRIFT   every customer account's cached balance equals the balance derived from its postings.
 *  4. SETTLEMENT      no transfer has been sitting half-done (debited but neither credited nor reversed) for longer
 *                     than `ledger.audit.stuck-after` - that money is stranded in SETTLEMENT.
 *
 * Results are also exported as gauges (neobank_ledger_*) that the alert rules watch.
 */
@Service
@Slf4j
public class LedgerAuditService {

    public record CurrencyTotals(String currency, BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced) {
    }

    public record GlBalance(String code, String name, String currency, String accountType,
                            BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal balance) {
    }

    public record StuckTransfer(UUID transactionId, Instant debitedAt) {
    }

    public record AccountDrift(UUID accountId, BigDecimal cachedBalance, BigDecimal postingsBalance) {
    }

    public record TrialBalance(Instant asOf, boolean healthy, List<CurrencyTotals> totals, List<GlBalance> glAccounts,
                               List<UUID> unbalancedJournals, List<StuckTransfer> stuckInSettlement,
                               List<AccountDrift> driftedAccounts) {
    }

    private final PostingRepository postingRepository;
    private final GlAccountRepository glAccountRepository;
    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final Clock clock;
    private final Duration stuckAfter;

    private final AtomicInteger unbalancedJournals = new AtomicInteger();
    private final AtomicInteger stuckTransfers = new AtomicInteger();
    private final AtomicInteger driftedAccounts = new AtomicInteger();
    private final AtomicInteger unbalancedCurrencies = new AtomicInteger();

    public LedgerAuditService(PostingRepository postingRepository,
                              GlAccountRepository glAccountRepository,
                              AccountRepository accountRepository,
                              JournalEntryRepository journalEntryRepository,
                              MeterRegistry meterRegistry,
                              Clock clock,
                              @Value("${ledger.audit.stuck-after:PT10M}") Duration stuckAfter) {
        this.postingRepository = postingRepository;
        this.glAccountRepository = glAccountRepository;
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
        Gauge.builder("neobank.ledger.unbalanced.journals", unbalancedJournals, AtomicInteger::get)
                .description("Journals whose debits and credits differ - must be 0").register(meterRegistry);
        Gauge.builder("neobank.ledger.unbalanced.currencies", unbalancedCurrencies, AtomicInteger::get)
                .description("Currencies whose trial balance does not net to zero - must be 0").register(meterRegistry);
        Gauge.builder("neobank.ledger.stuck.transfers", stuckTransfers, AtomicInteger::get)
                .description("Transfers debited but neither credited nor reversed after the threshold").register(meterRegistry);
        Gauge.builder("neobank.ledger.account.drift", driftedAccounts, AtomicInteger::get)
                .description("Accounts whose cached balance differs from their postings").register(meterRegistry);
    }

    /**
     * REPEATABLE_READ: all the cross-checks see ONE consistent snapshot. Under the default READ_COMMITTED a transfer
     * committing between two of the queries would show up as false drift.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TrialBalance trialBalance() {
        // ---- trial balance per currency
        Map<String, BigDecimal[]> byCurrency = new TreeMap<>();
        for (Object[] row : postingRepository.totalsByCurrency()) {
            BigDecimal[] t = byCurrency.computeIfAbsent((String) row[0], k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            int idx = row[1] == PostingDirection.DEBIT ? 0 : 1;
            t[idx] = t[idx].add((BigDecimal) row[2]);
        }
        List<CurrencyTotals> totals = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : byCurrency.entrySet()) {
            totals.add(new CurrencyTotals(e.getKey(), e.getValue()[0], e.getValue()[1],
                    e.getValue()[0].compareTo(e.getValue()[1]) == 0));
        }

        // ---- GL account balances (normal side depends on the account class)
        Map<UUID, BigDecimal[]> glTotals = new HashMap<>();
        for (Object[] row : postingRepository.glTotals()) {
            BigDecimal[] t = glTotals.computeIfAbsent((UUID) row[0], k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            int idx = row[1] == PostingDirection.DEBIT ? 0 : 1;
            t[idx] = t[idx].add((BigDecimal) row[2]);
        }
        List<GlBalance> glBalances = new ArrayList<>();
        for (GlAccount gl : glAccountRepository.findAll()) {
            BigDecimal[] t = glTotals.getOrDefault(gl.getId(), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal balance = gl.getAccountType() == GlAccountType.ASSET ? t[0].subtract(t[1]) : t[1].subtract(t[0]);
            glBalances.add(new GlBalance(gl.getCode(), gl.getName(), gl.getCurrency(), gl.getAccountType().name(), t[0], t[1], balance));
        }
        glBalances.sort(Comparator.comparing(GlBalance::currency).thenComparing(GlBalance::code));

        // ---- individual journals
        List<UUID> unbalanced = postingRepository.findUnbalancedJournals();

        // ---- cached balance vs postings
        Map<UUID, BigDecimal> postingBalances = new HashMap<>();
        for (Object[] row : postingRepository.customerBalances()) {
            postingBalances.put((UUID) row[0], (BigDecimal) row[1]);
        }
        List<AccountDrift> drift = new ArrayList<>();
        for (Account account : accountRepository.findAll()) {
            BigDecimal fromPostings = postingBalances.getOrDefault(account.getId(), BigDecimal.ZERO);
            if (account.getBalance().compareTo(fromPostings) != 0) {
                drift.add(new AccountDrift(account.getId(), account.getBalance(), fromPostings));
            }
        }

        // ---- stuck transfers
        List<StuckTransfer> stuck = new ArrayList<>();
        for (JournalEntry j : journalEntryRepository.findStuckInSettlement(clock.instant().minus(stuckAfter))) {
            stuck.add(new StuckTransfer(j.getTransactionId(), j.getCreatedAt()));
        }

        unbalancedJournals.set(unbalanced.size());
        unbalancedCurrencies.set((int) totals.stream().filter(t -> !t.balanced()).count());
        driftedAccounts.set(drift.size());
        stuckTransfers.set(stuck.size());

        boolean healthy = unbalanced.isEmpty() && drift.isEmpty() && stuck.isEmpty()
                && totals.stream().allMatch(CurrencyTotals::balanced);
        return new TrialBalance(clock.instant(), healthy, totals, glBalances, unbalanced, stuck, drift);
    }

    /** Scheduled entry point: runs the audit and logs loudly if anything is wrong. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TrialBalance runAudit() {
        TrialBalance tb = trialBalance();
        if (tb.healthy()) {
            log.info("Ledger integrity audit OK: {} currencies balanced, no drift, nothing stuck", tb.totals().size());
        } else {
            log.error("LEDGER INTEGRITY AUDIT FAILED: unbalancedJournals={} unbalancedCurrencies={} drift={} stuckTransfers={}",
                    tb.unbalancedJournals().size(), tb.totals().stream().filter(t -> !t.balanced()).count(),
                    tb.driftedAccounts().size(), tb.stuckInSettlement().size());
        }
        return tb;
    }
}
