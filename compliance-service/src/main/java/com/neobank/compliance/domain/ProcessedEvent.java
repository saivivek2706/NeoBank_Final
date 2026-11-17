package com.neobank.compliance.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka guarantees at-least-once delivery, meaning consumers WILL occasionally
 * see the same message twice (broker restarts, consumer rebalances, retries).
 * For a bank, processing "debit $500" twice is catastrophic, so every consumer
 * checks this table (in the same DB transaction as the business change) before
 * acting, and inserts a row afterwards. Combined with the outbox pattern this
 * gives end-to-end exactly-once *processing semantics* on top of an at-least-once broker.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    private UUID eventId;

    private Instant processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(UUID eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }
}
