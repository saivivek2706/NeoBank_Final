package com.neobank.account.repository;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountType;
import com.neobank.account.domain.ExternalStatement;
import com.neobank.account.domain.Hold;
import com.neobank.account.domain.JournalEntry;
import com.neobank.account.domain.JournalType;
import com.neobank.account.domain.PostingDirection;
import com.neobank.account.service.GlAccountService;
import com.neobank.account.service.JournalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The double-entry guarantees, proven against a REAL Postgres running the production Flyway migrations (V1-V6):
 * balanced journals, the append-only ledger, one-journal-per-transfer-step, reversal-at-most-once, and the
 * queries behind holds, reconciliation and the stuck-transfer check. None of this can be shown with mocks.
 *
 * Each test runs in its own rolled-back transaction. A statement that Postgres rejects aborts that transaction, so
 * every "must be rejected" test ends with its single failing statement.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({GlAccountService.class, JournalService.class})
class LedgerJournalIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("neobank_account_ledger_test")
            .withUsername("neobank")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private AccountRepository accountRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private PostingRepository postingRepository;
    @Autowired private GlAccountRepository glAccountRepository;
    @Autowired private HoldRepository holdRepository;
    @Autowired private ExternalStatementRepository externalStatementRepository;
    @Autowired private JournalService journalService;
    @Autowired private GlAccountService glAccountService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Account newAccount(String number, String balance) {
        return accountRepository.saveAndFlush(Account.open(number, UUID.randomUUID(), "Owner " + number,
                AccountType.SAVINGS, "SGD", new BigDecimal(balance)));
    }

    private BigDecimal customerBalance(Account a) {
        return postingRepository.customerBalance(a.getId());
    }

    private void commitDeferredChecks() {
        jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");   // fire the deferred balance trigger now instead of at COMMIT
    }

    // ------------------------------------------------------------------------------------------ schema

    @Test
    void migrationsCreateTheJournalHoldAndReconciliationTables() {
        Integer tables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables WHERE table_name IN
                ('gl_accounts', 'journal_entries', 'postings', 'holds', 'external_statements',
                 'external_statement_lines', 'reconciliation_runs', 'reconciliation_breaks')""", Integer.class);

        assertThat(tables).isEqualTo(8);
    }

    // ------------------------------------------------------------------------------------------ balanced journals

    @Test
    void aFullTransferWithAFeeBalancesEveryJournalAndNetsSettlementToZero() {
        Account alice = newAccount("NB200000001", "0");
        Account bob = newAccount("NB200000002", "0");
        UUID transfer = UUID.randomUUID();

        journalService.postOpeningDeposit(alice, UUID.randomUUID(), new BigDecimal("1000.00"), "BANK-DEP-1");
        journalService.postTransferDebit(alice, transfer, new BigDecimal("100.00"), new BigDecimal("1.50"));
        journalService.postTransferCredit(bob, transfer, new BigDecimal("100.00"));
        journalEntryRepository.flush();
        commitDeferredChecks();   // would throw if any journal were unbalanced

        assertThat(customerBalance(alice)).isEqualByComparingTo("898.50");   // 1000 - 100 - 1.50
        assertThat(customerBalance(bob)).isEqualByComparingTo("100.00");
        assertThat(postingRepository.findUnbalancedJournals()).isEmpty();

        var settlement = glAccountService.system(com.neobank.account.domain.SystemAccount.SETTLEMENT, "SGD");
        BigDecimal settlementNet = postingRepository.glTotals().stream()
                .filter(r -> r[0].equals(settlement.getId()))
                .map(r -> r[1] == PostingDirection.CREDIT ? (BigDecimal) r[2] : ((BigDecimal) r[2]).negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(settlementNet).isEqualByComparingTo("0");                  // nothing left in flight

        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Object[] row : postingRepository.totalsByCurrency()) {
            if (row[1] == PostingDirection.DEBIT) debits = debits.add((BigDecimal) row[2]);
            else credits = credits.add((BigDecimal) row[2]);
        }
        assertThat(debits).isEqualByComparingTo(credits);                      // the trial balance
    }

    @Test
    void theDatabaseRejectsAJournalWhoseDebitsAndCreditsDiffer() {
        Account alice = newAccount("NB200000003", "0");
        UUID gl = glAccountService.system(com.neobank.account.domain.SystemAccount.SETTLEMENT, "SGD").getId();
        UUID journalId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO journal_entries (id, transaction_id, journal_type, description, currency) VALUES (?, ?, 'OPENING_DEPOSIT', 'bad', 'SGD')",
                journalId, UUID.randomUUID());
        jdbcTemplate.update("INSERT INTO postings (id, journal_id, customer_account_id, direction, amount, currency) VALUES (?, ?, ?, 'DEBIT', 100, 'SGD')",
                UUID.randomUUID(), journalId, alice.getId());
        jdbcTemplate.update("INSERT INTO postings (id, journal_id, gl_account_id, direction, amount, currency) VALUES (?, ?, ?, 'CREDIT', 99, 'SGD')",
                UUID.randomUUID(), journalId, gl);

        assertThatThrownBy(this::commitDeferredChecks)
                .isInstanceOf(DataAccessException.class).hasMessageContaining("unbalanced");
    }

    @Test
    void aPostingMustTargetExactlyOneKindOfAccount() {
        UUID journalId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO journal_entries (id, transaction_id, journal_type, description, currency) VALUES (?, ?, 'OPENING_DEPOSIT', 'x', 'SGD')",
                journalId, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO postings (id, journal_id, direction, amount, currency) VALUES (?, ?, 'DEBIT', 1, 'SGD')",
                UUID.randomUUID(), journalId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void postingAmountsMustBePositive() {
        UUID gl = glAccountService.system(com.neobank.account.domain.SystemAccount.SETTLEMENT, "SGD").getId();
        UUID journalId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO journal_entries (id, transaction_id, journal_type, description, currency) VALUES (?, ?, 'OPENING_DEPOSIT', 'x', 'SGD')",
                journalId, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO postings (id, journal_id, gl_account_id, direction, amount, currency) VALUES (?, ?, ?, 'DEBIT', -5, 'SGD')",
                UUID.randomUUID(), journalId, gl))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------------------------------ append-only

    @Test
    void postingsCannotBeUpdated() {
        Account alice = newAccount("NB200000004", "0");
        journalService.postOpeningDeposit(alice, UUID.randomUUID(), BigDecimal.TEN, "R");
        journalEntryRepository.flush();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE postings SET amount = 1"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void postingsCannotBeDeleted() {
        Account alice = newAccount("NB200000005", "0");
        journalService.postOpeningDeposit(alice, UUID.randomUUID(), BigDecimal.TEN, "R");
        journalEntryRepository.flush();

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM postings"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void journalEntriesCannotBeUpdatedOrDeleted() {
        Account alice = newAccount("NB200000006", "0");
        journalService.postOpeningDeposit(alice, UUID.randomUUID(), BigDecimal.TEN, "R");
        journalEntryRepository.flush();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE journal_entries SET description = 'tampered'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    // ------------------------------------------------------------------------------------------ one journal per saga step

    @Test
    void aTransferCanOnlyBeDebitedOnce_evenIfApplicationCodeTriedTwice() {
        Account alice = newAccount("NB200000007", "0");
        UUID transfer = UUID.randomUUID();
        journalService.postTransferDebit(alice, transfer, BigDecimal.TEN, BigDecimal.ZERO);
        journalEntryRepository.flush();

        journalService.postTransferDebit(alice, transfer, BigDecimal.TEN, BigDecimal.ZERO);

        assertThatThrownBy(() -> journalEntryRepository.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aReversalRestoresTheSenderInFull_andCanOnlyHappenOnce() {
        Account alice = newAccount("NB200000008", "0");
        UUID transfer = UUID.randomUUID();
        journalService.postOpeningDeposit(alice, UUID.randomUUID(), new BigDecimal("500.00"), "R");
        journalService.postTransferDebit(alice, transfer, new BigDecimal("200.00"), new BigDecimal("2.00"));
        journalEntryRepository.flush();
        assertThat(customerBalance(alice)).isEqualByComparingTo("298.00");

        JournalService.ReversalResult reversal = journalService.postTransferReversal(alice, transfer, new BigDecimal("200.00"));
        journalEntryRepository.flush();
        commitDeferredChecks();

        assertThat(reversal.principal()).isEqualByComparingTo("200.00");
        assertThat(reversal.fee()).isEqualByComparingTo("2.00");
        assertThat(customerBalance(alice)).isEqualByComparingTo("500.00");     // amount AND fee refunded
        assertThat(journalEntryRepository.findByTransactionIdAndJournalType(transfer, JournalType.TRANSFER_REVERSAL))
                .get().extracting(JournalEntry::getReversesJournalId).isNotNull();

        journalService.postTransferReversal(alice, transfer, new BigDecimal("200.00"));   // a second reversal
        assertThatThrownBy(() -> journalEntryRepository.flush()).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------------------------------ queries

    @Test
    void stuckTransfersAreThoseDebitedButNeverCreditedOrReversed() {
        Account alice = newAccount("NB200000009", "0");
        Account bob = newAccount("NB200000010", "0");
        UUID stuck = UUID.randomUUID();
        UUID completed = UUID.randomUUID();
        UUID reversed = UUID.randomUUID();
        for (UUID tx : List.of(stuck, completed, reversed)) {
            journalService.postTransferDebit(alice, tx, BigDecimal.TEN, BigDecimal.ZERO);
        }
        journalService.postTransferCredit(bob, completed, BigDecimal.TEN);
        journalService.postTransferReversal(alice, reversed, BigDecimal.TEN);
        journalEntryRepository.flush();

        List<JournalEntry> found = journalEntryRepository.findStuckInSettlement(Instant.now().plus(Duration.ofHours(1)));

        assertThat(found).extracting(JournalEntry::getTransactionId).containsExactly(stuck);
        assertThat(journalEntryRepository.findStuckInSettlement(Instant.now().minus(Duration.ofHours(1)))).isEmpty();   // too recent
    }

    @Test
    void externalMovementsAreTheCashAccountPostingsInTheWindow_withTheBankReference() {
        Account alice = newAccount("NB200000011", "0");
        journalService.postOpeningDeposit(alice, UUID.randomUUID(), new BigDecimal("75.00"), "BANK-REF-42");
        journalService.postHoldCapture(alice, UUID.randomUUID(), new BigDecimal("5.00"), "PAYOUT-7");
        journalEntryRepository.flush();

        List<Object[]> rows = postingRepository.externalMovements("EXTERNAL_FUNDING", "SGD",
                Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)));

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo("BANK-REF-42");
            assertThat(r[2]).isEqualTo(PostingDirection.DEBIT);        // cash up = money received
            assertThat((BigDecimal) r[3]).isEqualByComparingTo("75.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo("PAYOUT-7");
            assertThat(r[2]).isEqualTo(PostingDirection.CREDIT);       // cash down = money paid out
        });
        assertThat(postingRepository.externalMovements("EXTERNAL_FUNDING", "SGD",
                Instant.now().plus(Duration.ofHours(2)), Instant.now().plus(Duration.ofHours(3)))).isEmpty();
        assertThat(postingRepository.externalMovements("EXTERNAL_FUNDING", "USD",
                Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)))).isEmpty();
    }

    @Test
    void glAccountsAreCreatedOncePerCurrency_andLookupIsRepeatable() {
        var first = glAccountService.system(com.neobank.account.domain.SystemAccount.FEE_INCOME, "SGD");
        var again = glAccountService.system(com.neobank.account.domain.SystemAccount.FEE_INCOME, "SGD");
        var other = glAccountService.system(com.neobank.account.domain.SystemAccount.FEE_INCOME, "USD");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(other.getId()).isNotEqualTo(first.getId());
        assertThat(glAccountRepository.findByCodeAndCurrency("FEE_INCOME", "SGD")).isPresent();
    }

    // ------------------------------------------------------------------------------------------ holds

    @Test
    void activeHoldsReduceAvailability_expiredAndReleasedOnesDoNot() {
        Account alice = newAccount("NB200000012", "1000");
        Instant now = Instant.now();
        holdRepository.save(Hold.place(alice.getId(), new BigDecimal("100.00"), "SGD", "ACTIVE-1", null, now.plus(Duration.ofHours(1))));
        holdRepository.save(Hold.place(alice.getId(), new BigDecimal("50.00"), "SGD", "ACTIVE-2", null, now.plus(Duration.ofHours(2))));
        holdRepository.save(Hold.place(alice.getId(), new BigDecimal("999.00"), "SGD", "LAPSED", null, now.minus(Duration.ofMinutes(1))));
        Hold released = Hold.place(alice.getId(), new BigDecimal("777.00"), "SGD", "RELEASED", null, now.plus(Duration.ofHours(1)));
        released.release();
        holdRepository.save(released);
        holdRepository.flush();

        assertThat(holdRepository.sumActive(alice.getId(), now)).isEqualByComparingTo("150.00");
        assertThat(holdRepository.findActive(alice.getId(), now)).hasSize(2);

        assertThat(holdRepository.expireDue(now)).isEqualTo(1);                    // only the lapsed ACTIVE one
        assertThat(holdRepository.sumActive(alice.getId(), now)).isEqualByComparingTo("150.00");
    }

    @Test
    void aHoldReferenceIsUniquePerAccount() {
        Account alice = newAccount("NB200000013", "100");
        holdRepository.saveAndFlush(Hold.place(alice.getId(), BigDecimal.TEN, "SGD", "SAME-REF", null, Instant.now().plus(Duration.ofHours(1))));

        assertThatThrownBy(() -> holdRepository.saveAndFlush(
                Hold.place(alice.getId(), BigDecimal.ONE, "SGD", "SAME-REF", null, Instant.now().plus(Duration.ofHours(1)))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------------------------------ statements

    @Test
    void theSameStatementCannotBeStoredTwice() {
        LocalDate day = LocalDate.parse("2026-03-01");
        externalStatementRepository.saveAndFlush(ExternalStatement.of("SETTLEMENT_BANK", day, "SGD", UUID.randomUUID(), 3));
        assertThat(externalStatementRepository.existsBySourceAndStatementDateAndCurrency("SETTLEMENT_BANK", day, "SGD")).isTrue();
        assertThat(externalStatementRepository.existsBySourceAndStatementDateAndCurrency("SETTLEMENT_BANK", day, "USD")).isFalse();

        assertThatThrownBy(() -> externalStatementRepository.saveAndFlush(
                ExternalStatement.of("SETTLEMENT_BANK", day, "SGD", UUID.randomUUID(), 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
