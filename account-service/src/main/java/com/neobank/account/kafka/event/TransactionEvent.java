package com.neobank.account.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Generic event envelope used across every step of the transfer saga.
 * `eventId` is what downstream idempotent consumers use to detect and skip
 * duplicate deliveries (Kafka is at-least-once, never exactly-once end-to-end).
 */
public record TransactionEvent(
        UUID eventId,
        UUID transactionId,
        UUID accountId,
        BigDecimal amount,
        String currency,
        String reason,   // populated on *_FAILED events, e.g. "INSUFFICIENT_BALANCE"
        Instant occurredAt
) {
    public static TransactionEvent of(UUID transactionId, UUID accountId, BigDecimal amount, String currency) {
        return new TransactionEvent(UUID.randomUUID(), transactionId, accountId, amount, currency, null, Instant.now());
    }

    public static TransactionEvent failed(UUID transactionId, UUID accountId, BigDecimal amount, String currency, String reason) {
        return new TransactionEvent(UUID.randomUUID(), transactionId, accountId, amount, currency, reason, Instant.now());
    }
}
