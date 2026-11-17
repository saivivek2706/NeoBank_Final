package com.neobank.audit.repository;

import com.neobank.audit.domain.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends MongoRepository<AuditEvent, String> {
    List<AuditEvent> findByTransactionIdOrderByOccurredAtAsc(UUID transactionId);

    List<AuditEvent> findByAccountIdOrderByOccurredAtDesc(UUID accountId);

    boolean existsByEventId(UUID eventId);

    /** The newest link in the hash chain (records without a sequence are pre-chain history and are ignored). */
    Optional<AuditEvent> findFirstBySequenceNotNullOrderBySequenceDesc();

    Optional<AuditEvent> findBySequence(Long sequence);

    /** Keyset pagination over the chain: the next `pageable.size` links after `sequence`, in order. */
    List<AuditEvent> findBySequenceGreaterThanOrderBySequenceAsc(Long sequence, Pageable pageable);

    long countBySequenceNotNull();

    long countBySequenceIsNull();
}
