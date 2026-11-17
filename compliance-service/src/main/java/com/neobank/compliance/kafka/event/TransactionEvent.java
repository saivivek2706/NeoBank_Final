package com.neobank.compliance.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Saga event as published by the account service (only the fields monitoring needs are used). */
public record TransactionEvent(
        UUID eventId,
        UUID transactionId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant occurredAt
) {
}
