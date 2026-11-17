package com.neobank.fraud.dto;

import com.neobank.fraud.domain.FraudAlert;
import com.neobank.fraud.domain.FraudRuleType;
import com.neobank.fraud.domain.FraudSeverity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FraudAlertResponse(
        UUID id,
        UUID transactionId,
        UUID accountId,
        BigDecimal amount,
        FraudRuleType ruleTriggered,
        FraudSeverity severity,
        String details,
        Instant flaggedAt
) {
    public static FraudAlertResponse from(FraudAlert a) {
        return new FraudAlertResponse(a.getId(), a.getTransactionId(), a.getAccountId(), a.getAmount(),
                a.getRuleTriggered(), a.getSeverity(), a.getDetails(), a.getFlaggedAt());
    }
}
