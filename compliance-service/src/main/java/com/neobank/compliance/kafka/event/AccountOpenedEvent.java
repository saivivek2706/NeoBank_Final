package com.neobank.compliance.kafka.event;

import java.time.Instant;
import java.util.UUID;

public record AccountOpenedEvent(
        UUID eventId,
        UUID accountId,
        UUID ownerId,
        String ownerName,
        String currency,
        Instant occurredAt
) {
}
