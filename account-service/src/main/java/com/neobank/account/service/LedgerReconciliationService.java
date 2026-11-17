package com.neobank.account.service;

import com.neobank.account.domain.Account;
import com.neobank.account.dto.ReconciliationResponse;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.LedgerEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The cached Account.balance column and the append-only ledger are written
 * in the same DB transaction (see AccountService), so they should never
 * disagree - but "should never" is exactly the kind of assumption a real
 * bank never trusts blindly. This is the automated check that would catch a
 * bug (a bad migration, a manual data fix, a code path that updates one but
 * not the other) before it silently corrupts a customer's displayed balance.
 *
 * In a real deployment, `reconcileAll` finding any mismatch should page
 * someone / open an incident, not just log a line - logging is this repo's
 * stand-in for that alerting integration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerReconciliationService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MeterRegistry meterRegistry;

    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new com.neobank.account.exception.AccountNotFoundException(accountId));
        BigDecimal ledgerDerived = ledgerEntryRepository.computeBalanceFromLedger(accountId);
        return ReconciliationResponse.of(accountId, account.getBalance(), ledgerDerived);
    }

    /** Sweeps every account periodically and logs any drift (single-replica via ShedLock). Still a full table scan - a real deployment would batch/paginate this. */
    @Scheduled(fixedDelayString = "${ledger.reconciliation.interval-ms:300000}")
    // ShedLock: with N replicas the sweep runs on exactly one of them per interval (lock row in the
    // `shedlock` table), instead of every replica scanning the whole accounts table independently.
    @SchedulerLock(name = "ledger-reconciliation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    @Transactional(readOnly = true)
    public void reconcileAll() {
        List<Account> accounts = accountRepository.findAll();
        meterRegistry.counter("neobank.ledger.reconciliation.sweeps").increment();
        int mismatches = 0;
        for (Account account : accounts) {
            BigDecimal ledgerDerived = ledgerEntryRepository.computeBalanceFromLedger(account.getId());
            if (account.getBalance().compareTo(ledgerDerived) != 0) {
                mismatches++;
                // neobank_ledger_reconciliation_drift_total - the alertable signal (any increase = page someone).
                meterRegistry.counter("neobank.ledger.reconciliation.drift").increment();
                log.error("LEDGER RECONCILIATION MISMATCH: account={} cachedBalance={} ledgerDerivedBalance={}",
                        account.getId(), account.getBalance(), ledgerDerived);
            }
        }
        if (mismatches == 0) {
            log.debug("Ledger reconciliation sweep: {} accounts checked, no mismatches", accounts.size());
        }
    }
}
