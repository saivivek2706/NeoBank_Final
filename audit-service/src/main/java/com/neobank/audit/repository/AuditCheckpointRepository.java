package com.neobank.audit.repository;

import com.neobank.audit.domain.AuditCheckpoint;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AuditCheckpointRepository extends MongoRepository<AuditCheckpoint, String> {
    List<AuditCheckpoint> findAllByOrderBySequenceAsc();

    Optional<AuditCheckpoint> findFirstByOrderBySequenceDesc();
}
