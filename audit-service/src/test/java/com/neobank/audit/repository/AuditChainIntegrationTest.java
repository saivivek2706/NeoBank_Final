package com.neobank.audit.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.audit.domain.AuditCheckpoint;
import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.service.AuditChainService;
import com.neobank.audit.service.AuditChainVerifier;
import com.neobank.audit.service.AuditHasher;
import com.neobank.audit.service.AuditService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hash chain against a REAL MongoDB: the unique-sequence trick that keeps the chain linear under concurrent writers, and
 * detection of every way a stored record can be tampered with by someone editing the database directly.
 */
@DataMongoTest
@Testcontainers
class AuditChainIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> mongo.getReplicaSetUrl("neobank_audit_chain_test"));
    }

    @Autowired private AuditEventRepository repository;
    @Autowired private AuditCheckpointRepository checkpointRepository;
    @Autowired private MongoTemplate mongoTemplate;

    private AuditChainService chain(int maxAttempts) {
        return new AuditChainService(repository, new SimpleMeterRegistry(), maxAttempts);
    }

    private AuditChainVerifier verifier() {
        return new AuditChainVerifier(repository, checkpointRepository, 7);
    }

    private AuditEvent event(String amount) {
        return AuditEvent.record(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "account.debit.completed",
                "DEBIT_COMPLETED", new BigDecimal(amount), "SGD", null, Instant.now(), "{\"amount\":\"" + amount + "\"}");
    }

    private void cleanSlate() {
        mongoTemplate.remove(new Query(), AuditEvent.class);
        mongoTemplate.remove(new Query(), AuditCheckpoint.class);
    }

    private void appendFive() {
        cleanSlate();
        AuditChainService chain = chain(25);
        for (int i = 1; i <= 5; i++) {
            chain.append(event(i + "00.50"));
        }
    }

    @Test
    void sequentialAppendsFormAnIntactChain_thatSurvivesAMongoRoundTrip() {
        appendFive();

        var v = verifier().verifyAll();

        assertThat(v.valid()).isTrue();
        assertThat(v.checked()).isEqualTo(5);
        List<AuditEvent> stored = repository.findAll().stream().sorted(java.util.Comparator.comparing(AuditEvent::getSequence)).toList();
        assertThat(stored).extracting(AuditEvent::getSequence).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(stored.get(0).getPreviousHash()).isEqualTo(AuditHasher.GENESIS_HASH);
        for (int i = 1; i < stored.size(); i++) {
            assertThat(stored.get(i).getPreviousHash()).isEqualTo(stored.get(i - 1).getHash());
            assertThat(AuditHasher.hash(stored.get(i))).isEqualTo(stored.get(i).getHash());   // recomputed from what Mongo returned
        }
    }

    @Test
    void editingARecordDirectlyInTheDatabaseIsDetected() {
        appendFive();

        mongoTemplate.updateFirst(Query.query(Criteria.where("sequence").is(3L)), Update.update("amount", "1.00"), AuditEvent.class);

        var v = verifier().verifyAll();
        assertThat(v.valid()).isFalse();
        assertThat(v.firstBreak().reason()).isEqualTo("HASH_MISMATCH");
        assertThat(v.firstBreak().sequence()).isEqualTo(3L);
    }

    @Test
    void deletingARecordDirectlyInTheDatabaseIsDetected() {
        appendFive();

        mongoTemplate.remove(Query.query(Criteria.where("sequence").is(2L)), AuditEvent.class);

        var v = verifier().verifyAll();
        assertThat(v.valid()).isFalse();
        assertThat(v.firstBreak().reason()).isEqualTo("SEQUENCE_GAP");
        assertThat(v.firstBreak().sequence()).isEqualTo(2L);
    }

    @Test
    void truncatingTheTailIsDetectedBecauseOfACheckpoint() {
        appendFive();
        AuditEvent head = repository.findFirstBySequenceNotNullOrderBySequenceDesc().orElseThrow();
        checkpointRepository.save(AuditCheckpoint.of(head.getSequence(), head.getHash()));
        assertThat(verifier().verifyAll().valid()).isTrue();

        mongoTemplate.remove(Query.query(Criteria.where("sequence").gte(4L)), AuditEvent.class);   // the chain is still self-consistent...

        var v = verifier().verifyAll();
        assertThat(v.valid()).isFalse();                                                                // ...but no longer as long as the checkpoint says
        assertThat(v.firstBreak().reason()).isEqualTo("CHECKPOINT_AHEAD");
    }

    @Test
    void aRedeliveredEventIsNotChainedTwice() {
        cleanSlate();
        AuditChainService chain = chain(25);
        AuditEvent first = event("10");
        AuditEvent redelivery = AuditEvent.record(first.getEventId(), first.getTransactionId(), first.getAccountId(),
                first.getTopic(), first.getEventType(), first.getAmount(), first.getCurrency(), null, Instant.now(), first.getRawPayload());

        assertThat(chain.append(first)).isTrue();
        assertThat(chain.append(redelivery)).isFalse();

        assertThat(repository.count()).isEqualTo(1);
        assertThat(verifier().verifyAll().valid()).isTrue();
    }

    @Test
    void concurrentWritersStillProduceOneGaplessLinearChain() throws Exception {
        cleanSlate();
        int writers = 8;
        int perWriter = 12;
        AuditChainService chain = chain(500);   // generous: this test deliberately maximises contention
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perWriter; i++) {
                    chain.append(event("5"));
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<AuditEvent> all = repository.findAll();
        assertThat(all).hasSize(writers * perWriter);
        Set<Long> sequences = all.stream().map(AuditEvent::getSequence).collect(Collectors.toSet());
        assertThat(sequences).hasSize(writers * perWriter);                                            // no duplicate positions
        assertThat(sequences).containsExactlyInAnyOrderElementsOf(
                java.util.stream.LongStream.rangeClosed(1, writers * perWriter).boxed().toList());      // and no gaps
        var v = verifier().verifyAll();
        assertThat(v.valid()).as("chain verifies after concurrent appends: " + v.firstBreak()).isTrue();
        assertThat(v.checked()).isEqualTo(writers * perWriter);
    }

    @Test
    void recordsFromBeforeTheChainCoexistWithIt_andAreReportedAsUnchained() {
        cleanSlate();
        repository.save(event("1"));   // legacy records: no sequence (several of them - the unique index on sequence is sparse)
        repository.save(event("2"));
        chain(25).append(event("3"));
        chain(25).append(event("4"));

        var v = verifier().verifyAll();

        assertThat(v.valid()).isTrue();
        assertThat(v.checked()).isEqualTo(2);
        assertThat(v.unchainedLegacyEvents()).isEqualTo(2);
    }

    @Test
    void anyPlatformEventCanBeAuditedAndChained() throws Exception {
        cleanSlate();
        AuditService service = new AuditService(chain(25));
        String payload = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"accountId\":\"" + UUID.randomUUID()
                + "\",\"ownerName\":\"Jane Doe\",\"occurredAt\":\"2026-03-10T12:00:00Z\"}";

        service.recordGeneric("account.opened", payload, new ObjectMapper().readTree(payload));

        AuditEvent stored = repository.findAll().get(0);
        assertThat(stored.getEventType()).isEqualTo("ACCOUNT_OPENED");
        assertThat(stored.getRawPayload()).isEqualTo(payload);
        assertThat(stored.getSequence()).isEqualTo(1L);
        assertThat(verifier().verifyAll().valid()).isTrue();
    }
}
