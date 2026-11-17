package com.neobank.account.repository;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountType;
import com.neobank.account.domain.LedgerEntry;
import com.neobank.account.domain.LedgerEntryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real Postgres container with Flyway applying the actual
 * V2__ledger_entries migration - this is what would catch, for example, a
 * mismatch between the entity's @Enumerated(STRING) LedgerEntryType and the
 * column's VARCHAR(10) width, which an in-memory H2 test would silently let
 * through.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class LedgerEntryRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("neobank_account_test")
            .withUsername("neobank")
            .withPassword("neobank");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void computeBalanceFromLedger_matchesNetOfCreditsAndDebits() {
        Account account = accountRepository.saveAndFlush(
                Account.open("NB999100001", UUID.randomUUID(), "Ledger Test", AccountType.SAVINGS, "SGD", BigDecimal.ZERO));

        ledgerEntryRepository.save(LedgerEntry.of(account.getId(), UUID.randomUUID(), LedgerEntryType.CREDIT,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), "Opening deposit"));
        ledgerEntryRepository.save(LedgerEntry.of(account.getId(), UUID.randomUUID(), LedgerEntryType.DEBIT,
                new BigDecimal("300.00"), new BigDecimal("700.00"), "Transfer debit"));
        ledgerEntryRepository.save(LedgerEntry.of(account.getId(), UUID.randomUUID(), LedgerEntryType.CREDIT,
                new BigDecimal("50.00"), new BigDecimal("750.00"), "Transfer credit"));

        BigDecimal derived = ledgerEntryRepository.computeBalanceFromLedger(account.getId());

        assertThat(derived).isEqualByComparingTo("750.00"); // 1000 - 300 + 50
    }

    @Test
    void computeBalanceFromLedger_returnsZero_forAccountWithNoEntries() {
        Account account = accountRepository.saveAndFlush(
                Account.open("NB999100002", UUID.randomUUID(), "Empty Ledger", AccountType.SAVINGS, "SGD", BigDecimal.ZERO));

        assertThat(ledgerEntryRepository.computeBalanceFromLedger(account.getId())).isEqualByComparingTo("0");
    }

    @Test
    void findByAccountIdOrderByCreatedAtDesc_returnsNewestFirst() throws InterruptedException {
        Account account = accountRepository.saveAndFlush(
                Account.open("NB999100003", UUID.randomUUID(), "History Test", AccountType.SAVINGS, "SGD", BigDecimal.ZERO));

        LedgerEntry first = ledgerEntryRepository.save(LedgerEntry.of(account.getId(), UUID.randomUUID(),
                LedgerEntryType.CREDIT, new BigDecimal("100.00"), new BigDecimal("100.00"), "First"));
        Thread.sleep(5); // ensure a distinct createdAt ordering, since both could land in the same millisecond otherwise
        LedgerEntry second = ledgerEntryRepository.save(LedgerEntry.of(account.getId(), UUID.randomUUID(),
                LedgerEntryType.CREDIT, new BigDecimal("50.00"), new BigDecimal("150.00"), "Second"));

        List<LedgerEntry> page = ledgerEntryRepository
                .findByAccountIdOrderByCreatedAtDesc(account.getId(), org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent();

        assertThat(page).hasSize(2);
        assertThat(page.get(0).getId()).isEqualTo(second.getId());
        assertThat(page.get(1).getId()).isEqualTo(first.getId());
    }
}
