package com.neobank.audit.service;

import com.neobank.audit.domain.AuditCheckpoint;
import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.repository.AuditCheckpointRepository;
import com.neobank.audit.repository.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/** The verifier against an in-memory chain, so every kind of tampering can be staged exactly. */
@ExtendWith(MockitoExtension.class)
class AuditChainVerifierTest {

    @Mock private AuditEventRepository repository;
    @Mock private AuditCheckpointRepository checkpointRepository;

    private final List<AuditEvent> chain = new ArrayList<>();
    private final List<AuditCheckpoint> checkpoints = new ArrayList<>();
    private AuditChainVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new AuditChainVerifier(repository, checkpointRepository, 3);   // tiny pages exercise the pagination
        lenient().when(repository.findBySequenceGreaterThanOrderBySequenceAsc(any(), any(Pageable.class))).thenAnswer(inv -> {
            long after = inv.getArgument(0);
            int size = ((Pageable) inv.getArgument(1)).getPageSize();
            return chain.stream().filter(e -> e.getSequence() > after).sorted(java.util.Comparator.comparing(AuditEvent::getSequence)).limit(size).toList();
        });
        lenient().when(repository.findBySequence(any())).thenAnswer(inv ->
                chain.stream().filter(e -> e.getSequence().equals(inv.getArgument(0))).findFirst());
        lenient().when(repository.findFirstBySequenceNotNullOrderBySequenceDesc()).thenAnswer(inv ->
                chain.stream().max(java.util.Comparator.comparing(AuditEvent::getSequence)));
        lenient().when(repository.countBySequenceIsNull()).thenReturn(0L);
        lenient().when(checkpointRepository.findAllByOrderBySequenceAsc()).thenAnswer(inv -> List.copyOf(checkpoints));
    }

    private void buildChain(int length) {
        String previous = AuditHasher.GENESIS_HASH;
        for (int i = 1; i <= length; i++) {
            AuditEvent e = AuditEvent.record(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "account.debit.completed",
                    "DEBIT_COMPLETED", new BigDecimal(i * 10), "SGD", null, Instant.now(), "{\"n\":" + i + "}");
            e.link(i, previous);
            e.seal(AuditHasher.hash(e));
            previous = e.getHash();
            chain.add(e);
        }
    }

    @Test
    void anIntactChainVerifies_acrossPages() {
        buildChain(8);

        var v = verifier.verifyAll();

        assertThat(v.valid()).isTrue();
        assertThat(v.checked()).isEqualTo(8);
        assertThat(v.headSequence()).isEqualTo(8L);
        assertThat(v.headHash()).isEqualTo(chain.get(7).getHash());
        assertThat(v.firstBreak()).isNull();
    }

    @Test
    void anEmptyChainIsValid() {
        var v = verifier.verifyAll();

        assertThat(v.valid()).isTrue();
        assertThat(v.checked()).isZero();
        assertThat(v.headHash()).isNull();
    }

    @Test
    void editingAStoredRecordIsDetected() {
        buildChain(6);
        ReflectionTestUtils.setField(chain.get(3), "amount", new BigDecimal("999999"));   // someone "fixed" an amount in the database

        var v = verifier.verifyAll();

        assertThat(v.valid()).isFalse();
        assertThat(v.firstBreak().reason()).isEqualTo("HASH_MISMATCH");
        assertThat(v.firstBreak().sequence()).isEqualTo(4L);
        assertThat(v.checked()).isEqualTo(3);                                              // 1-3 were fine
    }

    @Test
    void editingARecordAndRecomputingItsHashStillBreaksTheNextLink() {
        buildChain(6);
        AuditEvent forged = chain.get(2);
        ReflectionTestUtils.setField(forged, "amount", new BigDecimal("1"));
        forged.seal(AuditHasher.hash(forged));                                             // a careful attacker re-seals the record...

        var v = verifier.verifyAll();

        assertThat(v.valid()).isFalse();                                                   // ...but record 4 still points at the OLD hash
        assertThat(v.firstBreak().reason()).isEqualTo("BROKEN_LINK");
        assertThat(v.firstBreak().sequence()).isEqualTo(4L);
    }

    @Test
    void deletingARecordIsDetectedAsAGap() {
        buildChain(6);
        chain.remove(2);   // record 3 vanishes

        var v = verifier.verifyAll();

        assertThat(v.valid()).isFalse();
        assertThat(v.firstBreak().reason()).isEqualTo("SEQUENCE_GAP");
        assertThat(v.firstBreak().sequence()).isEqualTo(3L);
    }

    @Test
    void deletingTheTailIsCaughtByACheckpoint() {
        buildChain(6);
        checkpoints.add(AuditCheckpoint.of(5, chain.get(4).getHash()));
        chain.remove(5);
        chain.remove(4);   // the newest two records are removed: the chain itself is still internally consistent

        var v = verifier.verifyAll();

        assertThat(v.valid()).isFalse();
        assertThat(v.firstBreak().reason()).isEqualTo("CHECKPOINT_AHEAD");
    }

    @Test
    void aRewrittenHistoryNoLongerMatchesItsCheckpoint() {
        buildChain(6);
        checkpoints.add(AuditCheckpoint.of(4, "f".repeat(64)));   // what the hash of record 4 used to be

        var v = verifier.verifyAll();

        assertThat(v.valid()).isFalse();
        assertThat(v.firstBreak().reason()).isEqualTo("CHECKPOINT_MISMATCH");
        assertThat(v.checkpointsChecked()).isEqualTo(1);
    }

    @Test
    void matchingCheckpointsPass() {
        buildChain(6);
        checkpoints.add(AuditCheckpoint.of(3, chain.get(2).getHash()));
        checkpoints.add(AuditCheckpoint.of(6, chain.get(5).getHash()));

        var v = verifier.verifyAll();

        assertThat(v.valid()).isTrue();
        assertThat(v.checkpointsChecked()).isEqualTo(2);
    }

    @Test
    void verifyRecentOnlyWalksTheNewestLinks_butStillCatchesTamperingInThem() {
        buildChain(10);

        var ok = verifier.verifyRecent(4);
        assertThat(ok.valid()).isTrue();
        assertThat(ok.checked()).isEqualTo(4);
        assertThat(ok.fromSequence()).isEqualTo(6L);

        ReflectionTestUtils.setField(chain.get(8), "currency", "USD");
        assertThat(verifier.verifyRecent(4).firstBreak().reason()).isEqualTo("HASH_MISMATCH");
    }

    @Test
    void verifyRecentDoesNotLookAtOlderHistory() {
        buildChain(10);
        ReflectionTestUtils.setField(chain.get(1), "currency", "USD");   // tampering far outside the window

        assertThat(verifier.verifyRecent(3).valid()).isTrue();             // the windowed check is cheap by design...
        assertThat(verifier.verifyAll().valid()).isFalse();                // ...the full check finds it
    }

    @Test
    void anAnchorThatNoLongerExistsIsReported() {
        buildChain(5);
        chain.remove(2);

        var v = verifier.verifyFrom(3);

        assertThat(v.valid()).isFalse();
        assertThat(v.firstBreak().reason()).isEqualTo("SEQUENCE_GAP");
        assertThat(Optional.ofNullable(v.firstBreak()).isPresent()).isTrue();
    }
}
