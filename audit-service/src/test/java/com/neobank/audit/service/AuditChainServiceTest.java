package com.neobank.audit.service;

import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.repository.AuditEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditChainServiceTest {

    @Mock private AuditEventRepository repository;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private AuditChainService chain;

    @BeforeEach
    void setUp() {
        chain = new AuditChainService(repository, meters, 3);
    }

    private AuditEvent draft() {
        return AuditEvent.record(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "account.debit.completed",
                "DEBIT_COMPLETED", BigDecimal.TEN, "SGD", null, Instant.now(), "{}");
    }

    private AuditEvent sealedHead(long sequence) {
        AuditEvent head = draft();
        head.link(sequence, AuditHasher.GENESIS_HASH);
        head.seal(AuditHasher.hash(head));
        return head;
    }

    @Test
    void theFirstRecordLinksToTheGenesisHash() {
        when(repository.findFirstBySequenceNotNullOrderBySequenceDesc()).thenReturn(Optional.empty());
        AuditEvent event = draft();

        assertThat(chain.append(event)).isTrue();

        assertThat(event.getSequence()).isEqualTo(1L);
        assertThat(event.getPreviousHash()).isEqualTo(AuditHasher.GENESIS_HASH);
        assertThat(event.getHash()).isEqualTo(AuditHasher.hash(event)).hasSize(64);
        verify(repository).save(event);
    }

    @Test
    void eachRecordLinksToTheHashOfTheCurrentHead() {
        AuditEvent head = sealedHead(41);
        when(repository.findFirstBySequenceNotNullOrderBySequenceDesc()).thenReturn(Optional.of(head));
        AuditEvent event = draft();

        chain.append(event);

        assertThat(event.getSequence()).isEqualTo(42L);
        assertThat(event.getPreviousHash()).isEqualTo(head.getHash());
    }

    @Test
    void aRedeliveredEventIsAnIdempotentNoOp_notARetry() {
        when(repository.findFirstBySequenceNotNullOrderBySequenceDesc()).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DuplicateKeyException("eventId"));
        AuditEvent event = draft();
        when(repository.existsByEventId(event.getEventId())).thenReturn(true);

        assertThat(chain.append(event)).isFalse();

        verify(repository, times(1)).save(any());           // gave up immediately: this event is already in the chain
        assertThat(meters.get("neobank.audit.chain.retries").counter().count()).isZero();
    }

    @Test
    void losingASequenceRaceReReadsTheHeadAndTriesAgain() {
        AuditEvent oldHead = sealedHead(5);
        AuditEvent newHead = sealedHead(6);   // another writer appended 6 while we were computing
        when(repository.findFirstBySequenceNotNullOrderBySequenceDesc()).thenReturn(Optional.of(oldHead), Optional.of(newHead));
        AuditEvent event = draft();
        when(repository.existsByEventId(event.getEventId())).thenReturn(false);
        when(repository.save(event)).thenThrow(new DuplicateKeyException("sequence")).thenReturn(event);

        assertThat(chain.append(event)).isTrue();

        assertThat(event.getSequence()).isEqualTo(7L);                       // recomputed on top of the new head
        assertThat(event.getPreviousHash()).isEqualTo(newHead.getHash());
        assertThat(event.getHash()).isEqualTo(AuditHasher.hash(event));
        assertThat(meters.get("neobank.audit.chain.retries").counter().count()).isEqualTo(1.0);
    }

    @Test
    void contentionBeyondTheRetryBudgetFailsLoudly_soTheMessageIsRetriedOrDeadLettered() {
        when(repository.findFirstBySequenceNotNullOrderBySequenceDesc()).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DuplicateKeyException("sequence"));
        AuditEvent event = draft();
        when(repository.existsByEventId(event.getEventId())).thenReturn(false);

        assertThatThrownBy(() -> chain.append(event)).isInstanceOf(IllegalStateException.class).hasMessageContaining("3 attempts");
        verify(repository, times(3)).save(any());
    }

    @Test
    void aSuccessfulAppendNeverTouchesExistingRecords() {
        when(repository.findFirstBySequenceNotNullOrderBySequenceDesc()).thenReturn(Optional.empty());
        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);

        chain.append(draft());

        verify(repository).save(saved.capture());
        verify(repository, never()).deleteAll();
        verify(repository, never()).delete(any());
    }
}
