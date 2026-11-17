package com.neobank.audit.controller;

import com.neobank.audit.domain.AuditCheckpoint;
import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.repository.AuditCheckpointRepository;
import com.neobank.audit.repository.AuditEventRepository;
import com.neobank.audit.service.AuditChainJob;
import com.neobank.audit.service.AuditChainVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Proof that the audit trail has not been tampered with. ADMIN only (the whole service is). */
@RestController
@RequestMapping("/api/v1/audit/chain")
@RequiredArgsConstructor
@Tag(name = "Audit chain", description = "Tamper-evidence for the audit trail: verify the hash chain, read the head, list checkpoints")
public class AuditChainController {

    public record Head(Long sequence, String hash, Instant recordedAt, long chainedEvents, long unchainedLegacyEvents) {
    }

    public record CheckpointResponse(long sequence, String hash, Instant createdAt) {
        static CheckpointResponse from(AuditCheckpoint c) {
            return new CheckpointResponse(c.getSequence(), c.getHash(), c.getCreatedAt());
        }
    }

    private final AuditChainVerifier verifier;
    private final AuditChainJob chainJob;
    private final AuditEventRepository eventRepository;
    private final AuditCheckpointRepository checkpointRepository;

    @GetMapping("/head")
    @Operation(summary = "The current head of the chain",
            description = "Copy this (sequence + hash) to storage OUTSIDE this database on a schedule: it is what lets you prove later that history was not rewritten, even by someone who controls the whole database.")
    public Head head() {
        AuditEvent head = eventRepository.findFirstBySequenceNotNullOrderBySequenceDesc().orElse(null);
        return new Head(head == null ? null : head.getSequence(), head == null ? null : head.getHash(),
                head == null ? null : head.getRecordedAt(), eventRepository.countBySequenceNotNull(), eventRepository.countBySequenceIsNull());
    }

    @GetMapping("/verify")
    @Operation(summary = "Verify the hash chain",
            description = "Recomputes every hash and checks links, gaps and checkpoints. `recent=N` verifies only the newest N records; omit for the whole chain. `valid:false` names the first broken record and why.")
    public AuditChainVerifier.Verification verify(@RequestParam(required = false) Long recent) {
        return recent == null ? verifier.verifyAll() : verifier.verifyRecent(recent);
    }

    @GetMapping("/checkpoints")
    @Operation(summary = "Recorded checkpoints (sequence + hash, oldest first)")
    public List<CheckpointResponse> checkpoints() {
        return checkpointRepository.findAllByOrderBySequenceAsc().stream().map(CheckpointResponse::from).toList();
    }

    @PostMapping("/checkpoint")
    @Operation(summary = "Record a checkpoint of the current head now")
    public Head checkpointNow() {
        chainJob.checkpoint();
        return head();
    }
}
