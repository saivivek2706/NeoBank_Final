package com.neobank.account.repository;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a REAL Postgres container (not H2/mocks) with Flyway applying
 * the actual production migration scripts - this is what catches the class
 * of bug where your JPA entity and your SQL schema silently drift apart,
 * which an H2-in-memory test with ddl-auto=create would never expose.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AccountRepositoryIntegrationTest {

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

    @org.springframework.beans.factory.annotation.Autowired
    private AccountRepository accountRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void savesAndReloadsAccount_againstRealSchema() {
        Account account = Account.open("NB999000001", UUID.randomUUID(), "Test User",
                AccountType.CURRENT, "SGD", new BigDecimal("1000.00"));

        Account saved = accountRepository.save(account);
        entityManager.flush();
        entityManager.clear();

        Account reloaded = accountRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getAccountNumber()).isEqualTo("NB999000001");
        assertThat(reloaded.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(reloaded.getVersion()).isEqualTo(0L);
    }

    @Test
    void optimisticLocking_preventsLostUpdates_onConcurrentModification() {
        Account account = Account.open("NB999000002", UUID.randomUUID(), "Concurrent Test",
                AccountType.SAVINGS, "SGD", new BigDecimal("500.00"));
        Account saved = accountRepository.saveAndFlush(account);
        entityManager.clear();

        // Simulate two concurrent request threads loading the same row...
        Account copyA = accountRepository.findById(saved.getId()).orElseThrow();
        entityManager.detach(copyA);
        Account copyB = accountRepository.findById(saved.getId()).orElseThrow();

        // ...the first writer commits successfully...
        copyB.debit(new BigDecimal("100.00"));
        accountRepository.saveAndFlush(copyB);

        // ...the second, now-stale writer must fail rather than silently
        // overwrite the first writer's change (the "lost update" bug this
        // @Version field exists specifically to prevent).
        copyA.debit(new BigDecimal("50.00"));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(copyA))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void findByAccountNumber_returnsAccount_whenExists() {
        Account account = Account.open("NB999000003", UUID.randomUUID(), "Lookup Test",
                AccountType.SAVINGS, "USD", BigDecimal.ZERO);
        accountRepository.saveAndFlush(account);

        assertThat(accountRepository.findByAccountNumber("NB999000003")).isPresent();
        assertThat(accountRepository.findByAccountNumber("NB000000000")).isEmpty();
    }
}
