package com.neobank.fraud.repository;

import com.neobank.fraud.domain.FraudAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, UUID> {
    List<FraudAlert> findAllByOrderByFlaggedAtDesc();
    List<FraudAlert> findByAccountIdOrderByFlaggedAtDesc(UUID accountId);
}
