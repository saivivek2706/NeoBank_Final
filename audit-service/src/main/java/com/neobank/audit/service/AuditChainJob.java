package com.neobank.audit.service;

import com.neobank.audit.domain.AuditCheckpoint;
import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.repository.AuditCheckpointRepository;
import com.neobank.audit.repository.AuditEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the audit chain honest while the system runs: periodically verifies the recent chain (exporting the result as a
 * metric that alerts watch) and records a checkpoint of the head. Safe on several replicas: a checkpoint for a sequence
 * number is unique, so a second replica's identical checkpoint is simply rejected.
 */
@Component
@Slf4j
public class AuditChainJob {

    private final AuditChainVerifier verifier;
    private final AuditEventRepository eventRepository;
    private final AuditCheckpointRepository checkpointRepository;
    private final long verifyWindow;

    private final AtomicInteger chainValid = new AtomicInteger(1);
    private final AtomicLong headSequence = new AtomicLong();
    private final AtomicLong unchained = new AtomicLong();

    public AuditChainJob(AuditChainVerifier verifier, AuditEventRepository eventRepository,
                         AuditCheckpointRepository checkpointRepository, MeterRegistry meterRegistry,
                         @Value("${audit.chain.verify-window:50000}") long verifyWindow) {
        this.verifier = verifier;
        this.eventRepository = eventRepository;
        this.checkpointRepository = checkpointRepository;
        this.verifyWindow = verifyWindow;
        Gauge.builder("neobank.audit.chain.valid", chainValid, AtomicInteger::get)
                .description("1 while the audit hash chain verifies, 0 if tampering is detected").register(meterRegistry);
        Gauge.builder("neobank.audit.chain.head.sequence", headSequence, AtomicLong::get)
                .description("Sequence number of the newest audit record").register(meterRegistry);
        Gauge.builder("neobank.audit.chain.unchained.events", unchained, AtomicLong::get)
                .description("Audit records written before the hash chain existed (cannot be verified)").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${audit.chain.verify-interval-ms:900000}", initialDelayString = "${audit.chain.verify-initial-delay-ms:60000}")
    public void verify() {
        AuditChainVerifier.Verification v = verifier.verifyRecent(verifyWindow);
        chainValid.set(v.valid() ? 1 : 0);
        if (v.headSequence() != null) {
            headSequence.set(v.headSequence());
        }
        unchained.set(v.unchainedLegacyEvents());
        if (v.valid()) {
            log.info("Audit chain OK: {} recent records verified, head={}, {} checkpoints", v.checked(), v.headSequence(), v.checkpointsChecked());
        } else {
            log.error("AUDIT CHAIN TAMPERING DETECTED: {} at record {} - {}", v.firstBreak().reason(), v.firstBreak().sequence(), v.firstBreak().detail());
        }
    }

    @Scheduled(cron = "${audit.chain.checkpoint-cron:0 0 * * * *}")
    public void checkpoint() {
        Optional<AuditEvent> head = eventRepository.findFirstBySequenceNotNullOrderBySequenceDesc();
        if (head.isEmpty()) {
            return;
        }
        long lastCheckpoint = checkpointRepository.findFirstByOrderBySequenceDesc().map(AuditCheckpoint::getSequence).orElse(0L);
        if (head.get().getSequence() <= lastCheckpoint) {
            return;
        }
        try {
            checkpointRepository.save(AuditCheckpoint.of(head.get().getSequence(), head.get().getHash()));
            log.info("Audit checkpoint recorded at record {}", head.get().getSequence());
        } catch (DuplicateKeyException alreadyCheckpointedByAnotherReplica) {
            log.debug("Checkpoint for record {} already exists", head.get().getSequence());
        }
    }
}
