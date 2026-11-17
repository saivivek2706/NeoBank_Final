package com.neobank.transaction.repository;

import com.neobank.transaction.domain.OutboxEvent;
import com.neobank.transaction.domain.OutboxStatus;
import com.neobank.transaction.domain.Transaction;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a REAL Postgres with the production Flyway migrations (V1 schema, V2 idempotency columns +
 * partial unique index, V3 shedlock). This is what proves that duplicate-transfer protection is enforced by
 * the database itself - a mocked repository can't show that.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TransactionRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("neobank_transaction_test")
            .withUsername("neobank")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Transaction newTransfer(UUID caller, String key) {
        return Transaction.initiate(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), "SGD",
                caller, key, "f".repeat(64));
    }

    @Test
    void migrationsCreateIdempotencyColumnsAndShedlockTable() {
        Integer cols = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'transactions'
                  AND column_name IN ('requested_by', 'idempotency_key', 'request_fingerprint')""", Integer.class);
        Integer shedlock = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'shedlock'", Integer.class);

        assertThat(cols).isEqualTo(3);
        assertThat(shedlock).isEqualTo(1);
    }

    @Test
    void findsTransactionByCallerAndIdempotencyKey() {
        UUID caller = UUID.randomUUID();
        Transaction saved = transactionRepository.saveAndFlush(newTransfer(caller, "key-find-1"));

        assertThat(transactionRepository.findByRequestedByAndIdempotencyKey(caller, "key-find-1"))
                .get().extracting(Transaction::getId).isEqualTo(saved.getId());
        assertThat(transactionRepository.findByRequestedByAndIdempotencyKey(caller, "other-key")).isEmpty();
        assertThat(transactionRepository.findByRequestedByAndIdempotencyKey(UUID.randomUUID(), "key-find-1")).isEmpty();
    }

    @Test
    void databaseRejectsSecondTransferWithSameCallerAndKey() {
        UUID caller = UUID.randomUUID();
        transactionRepository.saveAndFlush(newTransfer(caller, "key-dup-1"));

        assertThatThrownBy(() -> transactionRepository.saveAndFlush(newTransfer(caller, "key-dup-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyIsAllowedForDifferentCallers() {
        transactionRepository.saveAndFlush(newTransfer(UUID.randomUUID(), "shared-key"));

        // Keys are scoped per caller, so one user can never collide with (or probe) another user's keys.
        transactionRepository.saveAndFlush(newTransfer(UUID.randomUUID(), "shared-key"));

        assertThat(transactionRepository.count()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void legacyRowsWithoutAKeyDoNotCollide() {
        Transaction a = Transaction.initiate(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "SGD");
        Transaction b = Transaction.initiate(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "SGD");

        transactionRepository.saveAndFlush(a);
        transactionRepository.saveAndFlush(b);

        assertThat(a.getId()).isNotEqualTo(b.getId());
    }

    @Test
    void outboxGaugesAndRetentionQueriesWork() {
        OutboxEvent sentOld = outboxEvent();
        sentOld.markSent();
        OutboxEvent pending = outboxEvent();
        outboxEventRepository.saveAndFlush(sentOld);
        outboxEventRepository.saveAndFlush(pending);
        // backdate the sent row beyond the retention window
        jdbcTemplate.update("UPDATE outbox_events SET sent_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)), sentOld.getId());
        entityManager.clear();

        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isGreaterThanOrEqualTo(1);

        int deleted = outboxEventRepository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, Instant.now().minus(3, ChronoUnit.DAYS));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(outboxEventRepository.findById(pending.getId())).isPresent();
        assertThat(outboxEventRepository.findById(sentOld.getId())).isEmpty();
    }

    @Test
    void processedEventsOlderThanRetentionArePurged() {
        UUID oldId = UUID.randomUUID();
        UUID freshId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?)",
                oldId, java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));
        jdbcTemplate.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?)",
                freshId, java.sql.Timestamp.from(Instant.now()));

        int deleted = processedEventRepository.deleteProcessedBefore(Instant.now().minus(14, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
        assertThat(processedEventRepository.existsById(oldId)).isFalse();
        assertThat(processedEventRepository.existsById(freshId)).isTrue();
    }

    private OutboxEvent outboxEvent() {
        return OutboxEvent.create("transaction", UUID.randomUUID().toString(), "TestEvent", "account.debit.requested", "{}");
    }
}
