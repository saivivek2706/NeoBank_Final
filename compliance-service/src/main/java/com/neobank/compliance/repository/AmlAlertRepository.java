package com.neobank.compliance.repository;

import com.neobank.compliance.domain.AmlAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AmlAlertRepository extends JpaRepository<AmlAlert, UUID> {
    boolean existsByDedupKey(String dedupKey);

    List<AmlAlert> findByCaseIdOrderByCreatedAtAsc(UUID caseId);

    Page<AmlAlert> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    long countByCreatedAtAfter(Instant since);
}
