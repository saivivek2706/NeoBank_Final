package com.neobank.transaction.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionEvent(
        UUID eventId,
        UUID transactionId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant occurredAt
) {
    public static TransactionEvent of(UUID transactionId, UUID accountId, BigDecimal amount, String currency) {
        return new TransactionEvent(UUID.randomUUID(), transactionId, accountId, amount, currency, null, Instant.now());
    }
}
