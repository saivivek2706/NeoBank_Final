package com.neobank.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.kafka.event.TransactionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Turns platform events into audit records and appends them to the tamper-evident hash chain (see AuditChainService).
 * Idempotent: the unique index on eventId makes a redelivered Kafka message a no-op.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditChainService chain;

    /** A saga event (transfer steps). */
    public void record(String topic, String rawPayload, TransactionEvent event) {
        chain.append(AuditEvent.record(event.eventId(), event.transactionId(), event.accountId(),
                topic, deriveEventType(topic), event.amount(), event.currency(), event.reason(),
                event.occurredAt(), rawPayload));
    }

    /**
     * Any other platform event that carries an `eventId` (account opened, compliance freeze/unfreeze commands, ...). Only
     * the common envelope is extracted; the full original payload is kept verbatim and covered by the record's hash.
     */
    public void recordGeneric(String topic, String rawPayload, JsonNode json) {
        UUID eventId = uuid(json, "eventId");
        if (eventId == null) {
            throw new IllegalArgumentException("Event on " + topic + " has no eventId and cannot be audited");
        }
        String reason = text(json, "reason");
        String action = text(json, "action");
        if (action != null) {
            reason = reason == null ? action : action + ": " + reason;
        }
        BigDecimal amount = json.hasNonNull("amount") ? json.get("amount").decimalValue() : null;
        Instant occurredAt = json.hasNonNull("occurredAt") ? Instant.parse(json.get("occurredAt").asText()) : null;
        chain.append(AuditEvent.record(eventId, uuid(json, "transactionId"), uuid(json, "accountId"), topic,
                topic.toUpperCase(Locale.ROOT).replace('.', '_'), amount, text(json, "currency"), reason, occurredAt, rawPayload));
    }

    private String deriveEventType(String topic) {
        // e.g. "account.debit.completed" -> "DEBIT_COMPLETED"
        int lastDot = topic.indexOf('.');
        return topic.substring(lastDot + 1).toUpperCase(Locale.ROOT).replace('.', '_');
    }

    private static UUID uuid(JsonNode json, String field) {
        return json.hasNonNull(field) ? UUID.fromString(json.get(field).asText()) : null;
    }

    private static String text(JsonNode json, String field) {
        return json.hasNonNull(field) ? json.get(field).asText() : null;
    }
}
