package com.neobank.audit.service;

import com.neobank.audit.domain.AuditCheckpoint;
import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.repository.AuditCheckpointRepository;
import com.neobank.audit.repository.AuditEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Walks the hash chain and proves it is intact: sequence numbers are gapless, every link points at the true hash of the one
 * before it, every record's stored hash matches its recomputed hash, and every checkpoint still matches the chain.
 *
 * What each failure means:
 *   HASH_MISMATCH        a record's content was altered after it was written
 *   BROKEN_LINK          a record points at a different predecessor than the one that is actually there
 *   SEQUENCE_GAP         a record was deleted (or inserted out of order)
 *   CHECKPOINT_MISMATCH  history before a checkpoint was rewritten
 *   CHECKPOINT_AHEAD     the tail of the chain was truncated (the chain is shorter than a checkpoint says it was)
 */
@Service
public class AuditChainVerifier {

    public record Break(long sequence, String reason, String detail) {
    }

    public record Verification(boolean valid, long checked, long fromSequence, Long headSequence, String headHash,
                               Break firstBreak, long unchainedLegacyEvents, int checkpointsChecked) {
    }

    private final AuditEventRepository repository;
    private final AuditCheckpointRepository checkpointRepository;
    private final int pageSize;

    public AuditChainVerifier(AuditEventRepository repository, AuditCheckpointRepository checkpointRepository,
                              @Value("${audit.chain.verify-page-size:500}") int pageSize) {
        this.repository = repository;
        this.checkpointRepository = checkpointRepository;
        this.pageSize = pageSize;
    }

    /** Verifies the whole chain. */
    public Verification verifyAll() {
        return verifyFrom(0);
    }

    /** Verifies only the newest `count` links (cheap enough to run frequently). */
    public Verification verifyRecent(long count) {
        long head = repository.findFirstBySequenceNotNullOrderBySequenceDesc().map(AuditEvent::getSequence).orElse(0L);
        return verifyFrom(Math.max(0, head - Math.max(count, 1)));
    }

    /**
     * Verifies every link AFTER `fromSequence` (0 = from the very beginning). The link at `fromSequence` itself is trusted
     * only as the anchor whose hash the next link must point at.
     */
    public Verification verifyFrom(long fromSequence) {
        String expectedPreviousHash = AuditHasher.GENESIS_HASH;
        if (fromSequence > 0) {
            Optional<AuditEvent> anchor = repository.findBySequence(fromSequence);
            if (anchor.isEmpty()) {
                return result(false, 0, fromSequence, new Break(fromSequence, "SEQUENCE_GAP", "Anchor record " + fromSequence + " is missing"));
            }
            expectedPreviousHash = anchor.get().getHash();
        }

        long expectedSequence = fromSequence + 1;
        long cursor = fromSequence;
        long checked = 0;
        String lastHash = expectedPreviousHash;
        Long lastSequence = fromSequence > 0 ? fromSequence : null;
        Break firstBreak = null;

        outer:
        while (true) {
            List<AuditEvent> page = repository.findBySequenceGreaterThanOrderBySequenceAsc(cursor, PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                break;
            }
            for (AuditEvent e : page) {
                if (e.getSequence() != expectedSequence) {
                    firstBreak = new Break(expectedSequence, "SEQUENCE_GAP",
                            "Expected record " + expectedSequence + " but found " + e.getSequence() + " (a record was removed)");
                    break outer;
                }
                if (!lastHash.equals(e.getPreviousHash())) {
                    firstBreak = new Break(e.getSequence(), "BROKEN_LINK", "Record " + e.getSequence() + " does not point at the hash of record " + (e.getSequence() - 1));
                    break outer;
                }
                if (!AuditHasher.hash(e).equals(e.getHash())) {
                    firstBreak = new Break(e.getSequence(), "HASH_MISMATCH", "Record " + e.getSequence() + " (event " + e.getEventId() + ") was modified after it was written");
                    break outer;
                }
                lastHash = e.getHash();
                lastSequence = e.getSequence();
                expectedSequence++;
                cursor = e.getSequence();
                checked++;
            }
        }

        int checkpointsChecked = 0;
        if (firstBreak == null) {
            Long headSeq = repository.findFirstBySequenceNotNullOrderBySequenceDesc().map(AuditEvent::getSequence).orElse(null);
            for (AuditCheckpoint cp : checkpointRepository.findAllByOrderBySequenceAsc()) {
                checkpointsChecked++;
                if (headSeq == null || cp.getSequence() > headSeq) {
                    firstBreak = new Break(cp.getSequence(), "CHECKPOINT_AHEAD",
                            "A checkpoint exists for record " + cp.getSequence() + " but the chain ends at " + headSeq + " (tail truncated)");
                    break;
                }
                Optional<AuditEvent> atCheckpoint = repository.findBySequence(cp.getSequence());
                if (atCheckpoint.isEmpty() || !cp.getHash().equals(atCheckpoint.get().getHash())) {
                    firstBreak = new Break(cp.getSequence(), "CHECKPOINT_MISMATCH", "Record " + cp.getSequence() + " no longer matches its checkpoint");
                    break;
                }
            }
        }

        Verification v = new Verification(firstBreak == null, checked, fromSequence, lastSequence, lastHash.equals(AuditHasher.GENESIS_HASH) ? null : lastHash,
                firstBreak, repository.countBySequenceIsNull(), checkpointsChecked);
        return v;
    }

    private Verification result(boolean valid, long checked, long from, Break firstBreak) {
        return new Verification(valid, checked, from, null, null, firstBreak, repository.countBySequenceIsNull(), 0);
    }
}
