package com.neobank.compliance.repository;

import com.neobank.compliance.domain.CaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CaseEventRepository extends JpaRepository<CaseEvent, UUID> {
    List<CaseEvent> findByCaseIdOrderByCreatedAtAsc(UUID caseId);
}
