package com.neobank.account.service;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountType;
import com.neobank.account.domain.GlAccount;
import com.neobank.account.domain.GlAccountType;
import com.neobank.account.domain.JournalEntry;
import com.neobank.account.domain.JournalType;
import com.neobank.account.domain.PostingDirection;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.GlAccountRepository;
import com.neobank.account.repository.JournalEntryRepository;
import com.neobank.account.repository.PostingRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerAuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    @Mock private PostingRepository postingRepository;
    @Mock private GlAccountRepository glAccountRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private JournalEntryRepository journalEntryRepository;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private LedgerAuditService service;

    @BeforeEach
    void setUp() {
        service = new LedgerAuditService(postingRepository, glAccountRepository, accountRepository, journalEntryRepository,
                meters, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10));
        lenient().when(postingRepository.totalsByCurrency()).thenReturn(List.of());
        lenient().when(postingRepository.glTotals()).thenReturn(List.of());
        lenient().when(postingRepository.findUnbalancedJournals()).thenReturn(List.of());
        lenient().when(postingRepository.customerBalances()).thenReturn(List.of());
        lenient().when(glAccountRepository.findAll()).thenReturn(List.of());
        lenient().when(accountRepository.findAll()).thenReturn(List.of());
        lenient().when(journalEntryRepository.findStuckInSettlement(any())).thenReturn(List.of());
    }

    private static GlAccount gl(UUID id, String code, GlAccountType type) {
        GlAccount gl = mock(GlAccount.class);
        lenient().when(gl.getId()).thenReturn(id);
        lenient().when(gl.getCode()).thenReturn(code);
        lenient().when(gl.getName()).thenReturn(code);
        lenient().when(gl.getCurrency()).thenReturn("SGD");
        lenient().when(gl.getAccountType()).thenReturn(type);
        return gl;
    }

    private static Account account(UUID id, String balance) {
        Account a = Account.open("NB" + id.toString().substring(0, 8), UUID.randomUUID(), "Owner", AccountType.SAVINGS, "SGD", new BigDecimal(balance));
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    @Test
    void aConsistentLedgerIsHealthy() {
        UUID acc = UUID.randomUUID();
        when(postingRepository.totalsByCurrency()).thenReturn(List.of(
                new Object[]{"SGD", PostingDirection.DEBIT, new BigDecimal("500.00")},
                new Object[]{"SGD", PostingDirection.CREDIT, new BigDecimal("500.00")}));
        when(accountRepository.findAll()).thenReturn(List.of(account(acc, "500.00")));
        when(postingRepository.customerBalances()).thenReturn(List.<Object[]>of(new Object[]{acc, new BigDecimal("500.0000")}));

        var tb = service.trialBalance();

        assertThat(tb.healthy()).isTrue();
        assertThat(tb.totals()).singleElement().satisfies(t -> assertThat(t.balanced()).isTrue());
        assertThat(tb.driftedAccounts()).isEmpty();
        assertThat(meters.get("neobank.ledger.account.drift").gauge().value()).isZero();
    }

    @Test
    void aCurrencyWhoseDebitsAndCreditsDifferIsFlagged() {
        when(postingRepository.totalsByCurrency()).thenReturn(List.of(
                new Object[]{"SGD", PostingDirection.DEBIT, new BigDecimal("500.00")},
                new Object[]{"SGD", PostingDirection.CREDIT, new BigDecimal("499.00")}));

        var tb = service.trialBalance();

        assertThat(tb.healthy()).isFalse();
        assertThat(tb.totals()).singleElement().satisfies(t -> assertThat(t.balanced()).isFalse());
        assertThat(meters.get("neobank.ledger.unbalanced.currencies").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void anAccountWhoseCachedBalanceDisagreesWithItsPostingsIsDrift() {
        UUID acc = UUID.randomUUID();
        when(accountRepository.findAll()).thenReturn(List.of(account(acc, "500.00")));
        when(postingRepository.customerBalances()).thenReturn(List.<Object[]>of(new Object[]{acc, new BigDecimal("450.00")}));

        var tb = service.trialBalance();

        assertThat(tb.healthy()).isFalse();
        assertThat(tb.driftedAccounts()).singleElement().satisfies(d -> {
            assertThat(d.accountId()).isEqualTo(acc);
            assertThat(d.cachedBalance()).isEqualByComparingTo("500.00");
            assertThat(d.postingsBalance()).isEqualByComparingTo("450.00");
        });
        assertThat(meters.get("neobank.ledger.account.drift").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void anAccountWithBalanceButNoPostingsIsDrift() {
        when(accountRepository.findAll()).thenReturn(List.of(account(UUID.randomUUID(), "10.00")));

        assertThat(service.trialBalance().driftedAccounts()).hasSize(1);
    }

    @Test
    void unbalancedJournalsAreReported() {
        UUID bad = UUID.randomUUID();
        when(postingRepository.findUnbalancedJournals()).thenReturn(List.of(bad));

        var tb = service.trialBalance();

        assertThat(tb.healthy()).isFalse();
        assertThat(tb.unbalancedJournals()).containsExactly(bad);
        assertThat(meters.get("neobank.ledger.unbalanced.journals").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void transfersStuckInSettlementAreReported() {
        UUID txId = UUID.randomUUID();
        JournalEntry stuck = JournalEntry.create(txId, JournalType.TRANSFER_DEBIT, "SGD", "Transfer debit", null, null);
        when(journalEntryRepository.findStuckInSettlement(NOW.minus(Duration.ofMinutes(10)))).thenReturn(List.of(stuck));

        var tb = service.trialBalance();

        assertThat(tb.healthy()).isFalse();
        assertThat(tb.stuckInSettlement()).singleElement().satisfies(s -> assertThat(s.transactionId()).isEqualTo(txId));
        assertThat(meters.get("neobank.ledger.stuck.transfers").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void glBalancesUseTheNormalSideOfEachAccountClass() {
        UUID cash = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        UUID fees = UUID.randomUUID();
        GlAccount cashAccount = gl(cash, "EXTERNAL_FUNDING", GlAccountType.ASSET);
        GlAccount settlementAccount = gl(settlement, "SETTLEMENT", GlAccountType.LIABILITY);
        GlAccount feeAccount = gl(fees, "FEE_INCOME", GlAccountType.INCOME);

        when(glAccountRepository.findAll()).thenReturn(
                List.of(cashAccount, settlementAccount, feeAccount));
        when(postingRepository.glTotals()).thenReturn(List.of(
                new Object[]{cash, PostingDirection.DEBIT, new BigDecimal("1000.00")},
                new Object[]{cash, PostingDirection.CREDIT, new BigDecimal("100.00")},
                new Object[]{settlement, PostingDirection.CREDIT, new BigDecimal("30.00")},
                new Object[]{settlement, PostingDirection.DEBIT, new BigDecimal("10.00")},
                new Object[]{fees, PostingDirection.CREDIT, new BigDecimal("2.50")}));

        var balances = service.trialBalance().glAccounts();

        assertThat(balances).filteredOn(b -> b.code().equals("EXTERNAL_FUNDING")).singleElement()
                .satisfies(b -> assertThat(b.balance()).isEqualByComparingTo("900.00"));      // asset: debit - credit
        assertThat(balances).filteredOn(b -> b.code().equals("SETTLEMENT")).singleElement()
                .satisfies(b -> assertThat(b.balance()).isEqualByComparingTo("20.00"));       // liability: credit - debit
        assertThat(balances).filteredOn(b -> b.code().equals("FEE_INCOME")).singleElement()
                .satisfies(b -> assertThat(b.balance()).isEqualByComparingTo("2.50"));
    }

    @Test
    void anAccountWithNoPostingsYetStillAppearsWithAZeroBalance() {
        GlAccount settlementAccount = gl(
                UUID.randomUUID(), "SETTLEMENT", GlAccountType.LIABILITY);

        when(glAccountRepository.findAll()).thenReturn(List.of(settlementAccount));
        assertThat(service.trialBalance().glAccounts()).singleElement()
                .satisfies(b -> assertThat(b.balance()).isEqualByComparingTo("0"));
    }

    @Test
    void runAuditReturnsTheSameResultAsTheTrialBalance() {
        assertThat(service.runAudit().healthy()).isTrue();
    }
}
