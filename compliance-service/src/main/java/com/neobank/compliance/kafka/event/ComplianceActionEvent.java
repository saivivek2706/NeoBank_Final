package com.neobank.compliance.kafka.event;

import java.time.Instant;
import java.util.UUID;

/** Command to the account service about one account. Mirrors the account service's own definition. */
public record ComplianceActionEvent(
        UUID eventId,
        UUID accountId,
        Action action,
        String reason,
        UUID caseId,
        Instant occurredAt
) {
    public enum Action {
        FREEZE,
        UNFREEZE
    }

    public static ComplianceActionEvent of(UUID accountId, Action action, String reason, UUID caseId) {
        return new ComplianceActionEvent(UUID.randomUUID(), accountId, action, reason, caseId, Instant.now());
    }
}
