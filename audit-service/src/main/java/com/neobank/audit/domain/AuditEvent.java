package com.neobank.audit.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * MongoDB is used here deliberately (rather than reusing Postgres like the
 * other services) to demonstrate polyglot persistence: the audit trail is a
 * write-heavy, schema-flexible, append-only log that's queried by document
 * shape rather than joined relationally - a natural fit for a document store,
 * and a common real-world choice for this exact use case (e.g. many banks'
 * audit/compliance logs run on Mongo or a dedicated event store).
 *
 * Records are NEVER updated or deleted by application code - only inserted.
 * That immutability is itself part of the audit guarantee.
 *
 * TAMPER EVIDENCE: every record is also a link in a hash chain. It carries a gapless {@code sequence}, the {@code hash}
 * of the record before it ({@code previousHash}) and its own {@code hash} over all of its content plus that previous
 * hash. Editing a stored record, deleting one, or slipping one in changes the hashes from that point onward, which
 * AuditChainVerifier detects. (Application code never updates rows, but anyone with database access could; the chain is
 * what makes that visible.) Records written before the chain existed have no sequence and are reported as "unchained".
 */
@Document(collection = "audit_events")
public class AuditEvent {

    @Id
    private String id;

    @Indexed(unique = true)
    private UUID eventId;

    @Indexed
    private UUID transactionId;

    @Indexed
    private UUID accountId;

    private String topic;
    private String eventType;
    private BigDecimal amount;
    private String currency;
    private String reason;
    private Instant occurredAt;
    private Instant recordedAt;
    private String rawPayload;

    /** Position in the hash chain (1, 2, 3, ...). Unique and sparse: records from before the chain have none. */
    @Indexed(unique = true, sparse = true)
    private Long sequence;

    /** Hash of the previous record in the chain (64 zeros for the first). */
    private String previousHash;

    /** SHA-256 over this record's content and previousHash - see AuditHasher. */
    private String hash;

    /** Public no-argument constructor required by JPA/MongoDB. */
    public AuditEvent() {
    }

    public String getId() {
        return this.id;
    }

    public UUID getEventId() {
        return this.eventId;
    }

    public UUID getTransactionId() {
        return this.transactionId;
    }

    public UUID getAccountId() {
        return this.accountId;
    }

    public String getTopic() {
        return this.topic;
    }

    public String getEventType() {
        return this.eventType;
    }

    public BigDecimal getAmount() {
        return this.amount;
    }

    public String getCurrency() {
        return this.currency;
    }

    public String getReason() {
        return this.reason;
    }

    public Instant getOccurredAt() {
        return this.occurredAt;
    }

    public Instant getRecordedAt() {
        return this.recordedAt;
    }

    public String getRawPayload() {
        return this.rawPayload;
    }

    public Long getSequence() {
        return this.sequence;
    }

    public String getPreviousHash() {
        return this.previousHash;
    }

    public String getHash() {
        return this.hash;
    }

    public static AuditEvent record(UUID eventId, UUID transactionId, UUID accountId, String topic,
                                     String eventType, BigDecimal amount, String currency, String reason,
                                     Instant occurredAt, String rawPayload) {
        AuditEvent e = new AuditEvent();
        e.setEventId(eventId);
        e.setTransactionId(transactionId);
        e.setAccountId(accountId);
        e.setTopic(topic);
        e.setEventType(eventType);
        e.setAmount(amount);
        e.setCurrency(currency);
        e.setReason(reason);
        // Mongo stores timestamps at millisecond precision. Truncating up front means the value that was hashed is
        // exactly the value read back later, so the hash can be recomputed during verification.
        e.setOccurredAt(occurredAt == null ? null : occurredAt.truncatedTo(ChronoUnit.MILLIS));
        e.setRecordedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        e.setRawPayload(rawPayload);
        return e;
    }

    /** Places this record in the chain (before it is sealed with its hash). */
    public void link(long newSequence, String newPreviousHash) {
        setSequence(newSequence);
        setPreviousHash(newPreviousHash);
        setHash(null);
    }

    public void seal(String newHash) {
        setHash(newHash);
    }

    /** After a failed insert the driver may already have assigned an id; clear it so a retry inserts a fresh document. */
    public void resetId() {
        setId(null);
    }
}
