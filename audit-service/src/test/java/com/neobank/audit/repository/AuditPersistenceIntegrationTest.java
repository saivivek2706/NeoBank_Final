package com.neobank.audit.repository;

import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.kafka.event.TransactionEvent;
import com.neobank.audit.service.AuditChainService;
import com.neobank.audit.service.AuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real MongoDB. Audit idempotency rests entirely on the UNIQUE index on eventId: Spring Data
 * MongoDB does not create @Indexed indexes unless spring.data.mongodb.auto-index-creation=true, so these
 * tests pin down both that the index really exists and that a redelivered Kafka event is stored only once.
 */
@DataMongoTest
@Testcontainers
class AuditPersistenceIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("neobank_audit_test"));
    }

    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private MongoTemplate mongoTemplate;

    private AuditService service() {
        return new AuditService(new AuditChainService(auditEventRepository, new SimpleMeterRegistry(), 25));
    }

    private TransactionEvent event(UUID eventId, UUID transactionId) {
        return new TransactionEvent(eventId, transactionId, UUID.randomUUID(), new BigDecimal("12.50"), "SGD", null, Instant.now());
    }

    @Test
    void uniqueIndexOnEventIdIsActuallyCreated() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(AuditEvent.class).getIndexInfo();

        assertThat(indexes).anySatisfy(index -> {
            assertThat(index.getIndexFields()).anyMatch(f -> f.getKey().equals("eventId"));
            assertThat(index.isUnique()).isTrue();
        });
    }

    @Test
    void theSequenceIndexIsUniqueAndSparse_soUnchainedLegacyRecordsCoexistWithTheChain() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(AuditEvent.class).getIndexInfo();

        assertThat(indexes).anySatisfy(index -> {
            assertThat(index.getIndexFields()).anyMatch(f -> f.getKey().equals("sequence"));
            assertThat(index.isUnique()).isTrue();
            assertThat(index.isSparse()).isTrue();
        });
    }

    @Test
    void redeliveredEventIsStoredExactlyOnce() {
        AuditService auditService = service();
        UUID eventId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        TransactionEvent event = event(eventId, transactionId);

        auditService.record("account.debit.completed", "{\"raw\":1}", event);
        auditService.record("account.debit.completed", "{\"raw\":1}", event);   // Kafka at-least-once redelivery

        assertThat(auditEventRepository.findAll().stream().filter(e -> e.getEventId().equals(eventId))).hasSize(1);
    }

    @Test
    void differentEventsForTheSameTransactionAreAllKept() {
        AuditService auditService = service();
        UUID transactionId = UUID.randomUUID();

        auditService.record("account.debit.completed", "{}", event(UUID.randomUUID(), transactionId));
        auditService.record("account.credit.completed", "{}", event(UUID.randomUUID(), transactionId));

        assertThat(auditEventRepository.findAll().stream().filter(e -> transactionId.equals(e.getTransactionId())))
                .hasSize(2)
                .extracting(AuditEvent::getEventType)
                .containsExactlyInAnyOrder("DEBIT_COMPLETED", "CREDIT_COMPLETED");
    }
}
