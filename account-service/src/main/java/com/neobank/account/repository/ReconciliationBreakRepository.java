package com.neobank.account.repository;

import com.neobank.account.domain.ReconciliationBreak;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationBreakRepository extends JpaRepository<ReconciliationBreak, UUID> {
    List<ReconciliationBreak> findByRunId(UUID runId);

    long countByResolvedFalse();
}
