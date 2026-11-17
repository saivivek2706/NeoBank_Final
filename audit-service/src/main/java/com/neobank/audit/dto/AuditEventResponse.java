package com.neobank.audit.dto;

import com.neobank.audit.domain.AuditEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID eventId,
        UUID transactionId,
        UUID accountId,
        String topic,
        String eventType,
        BigDecimal amount,
        String currency,
        String reason,
        Instant occurredAt,
        Instant recordedAt,
        Long sequence,
        String hash
) {
    public static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(e.getEventId(), e.getTransactionId(), e.getAccountId(), e.getTopic(),
                e.getEventType(), e.getAmount(), e.getCurrency(), e.getReason(), e.getOccurredAt(), e.getRecordedAt(), e.getSequence(), e.getHash());
    }
}
