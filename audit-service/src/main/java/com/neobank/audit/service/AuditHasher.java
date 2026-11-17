package com.neobank.audit.service;

import com.neobank.audit.domain.AuditEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Computes a record's hash: SHA-256 over a canonical, versioned string of everything that matters about it - its position
 * (sequence), the previous record's hash, and every business field - with the raw payload folded in as its own digest.
 *
 * "Canonical" matters: the same record must produce the same string every time, years later, on any machine. Fields are
 * joined with '|' in a fixed order, absent values are empty, amounts are written without trailing zeros, and timestamps
 * are epoch milliseconds (Mongo's own precision).
 */
public final class AuditHasher {

    public static final String GENESIS_HASH = "0".repeat(64);
    private static final String VERSION = "v1";

    private AuditHasher() {
    }

    /** Hash of `event` as currently linked (sequence + previousHash must be set). */
    public static String hash(AuditEvent e) {
        String canonical = String.join("|",
                VERSION,
                String.valueOf(e.getSequence()),
                nz(e.getPreviousHash()),
                nz(e.getEventId()),
                nz(e.getTransactionId()),
                nz(e.getAccountId()),
                nz(e.getTopic()),
                nz(e.getEventType()),
                e.getAmount() == null ? "" : e.getAmount().stripTrailingZeros().toPlainString(),
                nz(e.getCurrency()),
                nz(e.getReason()),
                millis(e.getOccurredAt()),
                millis(e.getRecordedAt()),
                sha256(nz(e.getRawPayload())));
        return sha256(canonical);
    }

    private static String nz(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String millis(Instant instant) {
        return instant == null ? "" : String.valueOf(instant.toEpochMilli());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by every JVM", e);
        }
    }
}
