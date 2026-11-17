package com.neobank.account.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * OUTBOX PATTERN
 * ---------------
 * Problem it solves: if a service updates its own DB (e.g. debits a balance)
 * and then separately calls kafkaTemplate.send(...), there's a window where the
 * DB commit succeeds but the process crashes before the Kafka publish happens
 * (or vice versa). In a bank, "money moved but nobody was told" is unacceptable.
 *
 * Solution: write the business row AND this outbox row in the SAME local DB
 * transaction (guaranteed atomic by Postgres). A separate poller (OutboxRelay)
 * reads unsent rows and publishes them to Kafka, marking them SENT only after
 * the broker acknowledges. If the poller crashes mid-publish, it just retries
 * next tick — Kafka producer idempotence + our own de-dup on eventId prevents
 * this from causing double-processing downstream.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    // Plain TEXT column, not @Lob: Hibernate 6 maps @Lob String -> Postgres
    // "oid" (large object) by default, which doesn't match the plain TEXT
    // column this project's migration declares - that mismatch fails schema
    // validation at startup ("expecting [oid], found [text]"). A JSON event
    // payload is well within TEXT's ~1GB limit, so @Lob was never needed here.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    private int attempts;

    public static OutboxEvent create(String aggregateType, String aggregateId,
                                      String eventType, String topic, String payloadJson) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID();
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setTopic(topic);
        event.setPayload(payloadJson);
        event.setStatus(OutboxStatus.PENDING);
        event.setCreatedAt(Instant.now());
        event.setAttempts(0);
        return event;
    }

    public void markSent() {
        setStatus(OutboxStatus.SENT);
        setSentAt(Instant.now());
    }

    public void markFailed() {
        setAttempts(getAttempts() + 1);
        if (getAttempts() >= 5) {
            setStatus(OutboxStatus.DEAD);
        }
    }
}
