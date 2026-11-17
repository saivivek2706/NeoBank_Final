package com.neobank.account.repository;

import com.neobank.account.domain.ExternalStatementLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExternalStatementLineRepository extends JpaRepository<ExternalStatementLine, UUID> {
    List<ExternalStatementLine> findByStatementId(UUID statementId);
}
