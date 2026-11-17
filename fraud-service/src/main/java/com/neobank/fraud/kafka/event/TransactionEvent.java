package com.neobank.fraud.kafka.event;

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
) {}
