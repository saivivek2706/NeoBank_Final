package com.neobank.audit.domain;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A periodic note of "the chain was at sequence N with hash H". Verification checks that each checkpoint still matches
 * the chain, which catches what a chain alone cannot: truncation of the tail, or a rewrite of recent history. The latest
 * checkpoint (GET /api/v1/audit/chain/head) is also meant to be copied OUTSIDE this database - to object storage with
 * retention lock, a ticket, a signed email - so that even someone who controls the whole database cannot rewrite the past
 * unnoticed.
 */
@Document(collection = "audit_checkpoints")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class AuditCheckpoint {

    @Id
    private String id;

    @Indexed(unique = true)
    private long sequence;

    private String hash;

    private Instant createdAt;

    public static AuditCheckpoint of(long sequence, String hash) {
        AuditCheckpoint c = new AuditCheckpoint();
        c.setSequence(sequence);
        c.setHash(hash);
        c.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return c;
    }
}
