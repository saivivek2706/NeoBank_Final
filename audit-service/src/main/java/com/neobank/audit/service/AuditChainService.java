package com.neobank.audit.service;

import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.repository.AuditEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Appends records to the hash chain.
 *
 * The chain must be linear even with several consumer threads and replicas writing at once. Rather than a lock, it relies on
 * the UNIQUE index on `sequence`: a writer reads the current head, builds the next link and inserts it. If another writer got
 * there first, Mongo rejects the duplicate sequence, and this writer re-reads the head and tries again. A duplicate EVENT id
 * (a redelivered Kafka message) is told apart from that race and treated as the idempotent no-op it is.
 *
 * The cost is that chain appends are serialised. Audit volume (one record per saga step) is modest, so that is an acceptable
 * trade for a tamper-evident log; `audit.chain.max-attempts` bounds the retries under heavy contention.
 */
@Service
@Slf4j
public class AuditChainService {

    private final AuditEventRepository repository;
    private final int maxAttempts;
    private final Counter contention;

    public AuditChainService(AuditEventRepository repository, MeterRegistry meterRegistry,
                             @Value("${audit.chain.max-attempts:25}") int maxAttempts) {
        this.repository = repository;
        this.maxAttempts = maxAttempts;
        this.contention = Counter.builder("neobank.audit.chain.retries")
                .description("Chain appends that lost a sequence race and had to retry").register(meterRegistry);
    }

    /** @return true if the event was appended, false if an event with the same id was already recorded */
    public boolean append(AuditEvent event) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Optional<AuditEvent> head = repository.findFirstBySequenceNotNullOrderBySequenceDesc();
            long sequence = head.map(h -> h.getSequence() + 1).orElse(1L);
            String previousHash = head.map(AuditEvent::getHash).orElse(AuditHasher.GENESIS_HASH);

            event.link(sequence, previousHash);
            event.seal(AuditHasher.hash(event));
            try {
                repository.save(event);
                return true;
            } catch (DuplicateKeyException ex) {
                if (repository.existsByEventId(event.getEventId())) {
                    log.debug("Duplicate audit event {} - already recorded, skipping", event.getEventId());
                    return false;
                }
                contention.increment();   // somebody else took this sequence number: re-read the head and retry
                event.resetId();
            }
        }
        throw new IllegalStateException("Could not append to the audit chain after " + maxAttempts + " attempts");
    }
}
