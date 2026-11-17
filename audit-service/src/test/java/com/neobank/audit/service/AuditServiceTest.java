package com.neobank.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.audit.domain.AuditEvent;
import com.neobank.audit.kafka.event.TransactionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private AuditChainService chain;

    private AuditService auditService;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        auditService = new AuditService(chain);
    }

    private AuditEvent appended() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(chain).append(captor.capture());
        return captor.getValue();
    }

    @Test
    void record_appendsAnAuditEvent_withDerivedEventType() {
        TransactionEvent event = new TransactionEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.00"), "SGD", null, Instant.now());

        auditService.record("account.debit.completed", "{\"raw\":\"payload\"}", event);

        AuditEvent e = appended();
        assertThat(e.getEventType()).isEqualTo("DEBIT_COMPLETED");
        assertThat(e.getTopic()).isEqualTo("account.debit.completed");
        assertThat(e.getEventId()).isEqualTo(event.eventId());
        assertThat(e.getRawPayload()).isEqualTo("{\"raw\":\"payload\"}");
    }

    @Test
    void record_truncatesTimestampsToTheMillisecondsMongoStores() {
        Instant withNanos = Instant.parse("2026-03-10T12:00:00.123456789Z");
        TransactionEvent event = new TransactionEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.TEN, "SGD", null, withNanos);

        auditService.record("account.debit.completed", "{}", event);

        // otherwise the hash computed at write time could never be reproduced from what Mongo stores
        assertThat(appended().getOccurredAt()).isEqualTo(Instant.parse("2026-03-10T12:00:00.123Z"));
    }

    @Test
    void recordGeneric_capturesTheCommonEnvelopeOfAnyPlatformEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String payload = "{\"eventId\":\"" + eventId + "\",\"accountId\":\"" + accountId + "\",\"action\":\"FREEZE\","
                + "\"reason\":\"sanctions hit\",\"caseId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-03-10T12:00:00Z\"}";

        auditService.recordGeneric("compliance.account.action", payload, json.readTree(payload));

        AuditEvent e = appended();
        assertThat(e.getEventId()).isEqualTo(eventId);
        assertThat(e.getAccountId()).isEqualTo(accountId);
        assertThat(e.getEventType()).isEqualTo("COMPLIANCE_ACCOUNT_ACTION");
        assertThat(e.getReason()).isEqualTo("FREEZE: sanctions hit");
        assertThat(e.getTransactionId()).isNull();
        assertThat(e.getRawPayload()).isEqualTo(payload);          // the full original is kept verbatim
    }

    @Test
    void recordGeneric_refusesAnEventWithoutAnId_soItIsDeadLetteredNotLost() throws Exception {
        String payload = "{\"accountId\":\"" + UUID.randomUUID() + "\"}";

        assertThatThrownBy(() -> auditService.recordGeneric("account.opened", payload, json.readTree(payload)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventId");
        verifyNoInteractions(chain);
    }
}
