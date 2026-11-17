package com.neobank.account.kafka.event;

import java.time.Instant;
import java.util.UUID;

/** A command from the compliance service about one account, e.g. FREEZE after a confirmed sanctions hit. */
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
}
