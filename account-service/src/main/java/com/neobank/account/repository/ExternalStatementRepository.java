package com.neobank.account.repository;

import com.neobank.account.domain.ExternalStatement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.UUID;

public interface ExternalStatementRepository extends JpaRepository<ExternalStatement, UUID> {
    boolean existsBySourceAndStatementDateAndCurrency(String source, LocalDate statementDate, String currency);
}
