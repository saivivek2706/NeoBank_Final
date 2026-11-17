package com.neobank.audit.service;

import com.neobank.audit.domain.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHasherTest {

    private static final UUID EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TX = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACCOUNT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant WHEN = Instant.parse("2026-03-10T12:00:00.123Z");

    private AuditEvent linked(long sequence, String previousHash) {
        AuditEvent e = AuditEvent.record(EVENT, TX, ACCOUNT, "account.debit.completed", "DEBIT_COMPLETED",
                new BigDecimal("100.50"), "SGD", "ok", WHEN, "{\"a\":1}");
        ReflectionTestUtils.setField(e, "recordedAt", WHEN);
        e.link(sequence, previousHash);
        return e;
    }

    @Test
    void theHashIsDeterministic_and64HexCharacters() {
        String h1 = AuditHasher.hash(linked(1, AuditHasher.GENESIS_HASH));
        String h2 = AuditHasher.hash(linked(1, AuditHasher.GENESIS_HASH));

        assertThat(h1).isEqualTo(h2).matches("[0-9a-f]{64}");
    }

    @Test
    void aStableKnownVector_soTheCanonicalFormCanNeverChangeUnnoticed() {
        // If this changes, every previously written audit record would stop verifying. The canonical form is a contract.
        assertThat(AuditHasher.hash(linked(1, AuditHasher.GENESIS_HASH)))
                .isEqualTo("191d204d50f849974c9e5a2f5326815d97be57f377662d5b52f107b962d36761");
    }

    @Test
    void everyFieldInfluencesTheHash() {
        String base = AuditHasher.hash(linked(1, AuditHasher.GENESIS_HASH));

        assertThat(AuditHasher.hash(linked(2, AuditHasher.GENESIS_HASH))).isNotEqualTo(base);                 // position
        assertThat(AuditHasher.hash(linked(1, "f".repeat(64)))).isNotEqualTo(base);                           // previous hash

        AuditEvent amount = linked(1, AuditHasher.GENESIS_HASH);
        ReflectionTestUtils.setField(amount, "amount", new BigDecimal("100.51"));
        assertThat(AuditHasher.hash(amount)).isNotEqualTo(base);

        AuditEvent currency = linked(1, AuditHasher.GENESIS_HASH);
        ReflectionTestUtils.setField(currency, "currency", "USD");
        assertThat(AuditHasher.hash(currency)).isNotEqualTo(base);

        AuditEvent account = linked(1, AuditHasher.GENESIS_HASH);
        ReflectionTestUtils.setField(account, "accountId", UUID.randomUUID());
        assertThat(AuditHasher.hash(account)).isNotEqualTo(base);

        AuditEvent payload = linked(1, AuditHasher.GENESIS_HASH);
        ReflectionTestUtils.setField(payload, "rawPayload", "{\"a\":2}");
        assertThat(AuditHasher.hash(payload)).isNotEqualTo(base);

        AuditEvent when = linked(1, AuditHasher.GENESIS_HASH);
        ReflectionTestUtils.setField(when, "occurredAt", WHEN.plusMillis(1));
        assertThat(AuditHasher.hash(when)).isNotEqualTo(base);

        AuditEvent reason = linked(1, AuditHasher.GENESIS_HASH);
        ReflectionTestUtils.setField(reason, "reason", "different");
        assertThat(AuditHasher.hash(reason)).isNotEqualTo(base);
    }

    @Test
    void decimalScaleDoesNotChangeTheHash_butTheValueDoes() {
        AuditEvent a = linked(1, AuditHasher.GENESIS_HASH);
        ReflectionTestUtils.setField(a, "amount", new BigDecimal("100.5000"));

        assertThat(AuditHasher.hash(a)).isEqualTo(AuditHasher.hash(linked(1, AuditHasher.GENESIS_HASH)));   // 100.50 == 100.5000
    }

    @Test
    void absentOptionalFieldsAreHandled() {
        AuditEvent e = AuditEvent.record(EVENT, null, null, "account.opened", "ACCOUNT_OPENED", null, null, null, null, "{}");
        e.link(1, AuditHasher.GENESIS_HASH);

        assertThat(AuditHasher.hash(e)).matches("[0-9a-f]{64}");
    }

    @Test
    void theHashDoesNotDependOnTheStoredHashFieldItself() {
        AuditEvent e = linked(1, AuditHasher.GENESIS_HASH);
        String before = AuditHasher.hash(e);
        e.seal("anything");

        assertThat(AuditHasher.hash(e)).isEqualTo(before);
    }
}
