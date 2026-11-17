package com.neobank.compliance.repository;

import com.neobank.compliance.domain.AmlCase;
import com.neobank.compliance.domain.CaseStatus;
import com.neobank.compliance.domain.CaseType;
import com.neobank.compliance.domain.Severity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AmlCaseRepository extends JpaRepository<AmlCase, UUID> {

    /** The single open case (if any) for this account and type; the partial unique index guarantees there is at most one. */
    @Query("SELECT c FROM AmlCase c WHERE c.subjectAccountId = :accountId AND c.caseType = :type "
            + "AND c.status <> com.neobank.compliance.domain.CaseStatus.CLOSED")
    Optional<AmlCase> findOpen(@Param("accountId") UUID accountId, @Param("type") CaseType type);

    Optional<AmlCase> findByCaseNumber(String caseNumber);

    Page<AmlCase> findByStatus(CaseStatus status, Pageable pageable);

    @Query("SELECT c FROM AmlCase c WHERE c.status <> com.neobank.compliance.domain.CaseStatus.CLOSED")
    Page<AmlCase> findAllOpen(Pageable pageable);

    @Query("SELECT c FROM AmlCase c WHERE c.assignee = :assignee AND c.status <> com.neobank.compliance.domain.CaseStatus.CLOSED")
    Page<AmlCase> findOpenAssignedTo(@Param("assignee") UUID assignee, Pageable pageable);

    @Query("SELECT COUNT(c) FROM AmlCase c WHERE c.status <> com.neobank.compliance.domain.CaseStatus.CLOSED")
    long countOpen();

    @Query("SELECT COUNT(c) FROM AmlCase c WHERE c.status <> com.neobank.compliance.domain.CaseStatus.CLOSED AND c.dueAt < :now")
    long countOverdue(@Param("now") Instant now);

    @Query("SELECT COUNT(c) FROM AmlCase c WHERE c.status <> com.neobank.compliance.domain.CaseStatus.CLOSED AND c.priority = :priority")
    long countOpenByPriority(@Param("priority") Severity priority);

    @Query(value = "SELECT nextval('case_number_seq')", nativeQuery = true)
    long nextCaseSequence();
}
