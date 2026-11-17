package com.neobank.account.kafka.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Announces a newly opened account. The compliance service screens {@code ownerName} against sanctions
 * watchlists. Carries only what screening needs - no balances, no account numbers.
 */
public record AccountOpenedEvent(
        UUID eventId,
        UUID accountId,
        UUID ownerId,
        String ownerName,
        String currency,
        Instant occurredAt
) {
    public static AccountOpenedEvent of(UUID accountId, UUID ownerId, String ownerName, String currency) {
        return new AccountOpenedEvent(UUID.randomUUID(), accountId, ownerId, ownerName, currency, Instant.now());
    }
}
