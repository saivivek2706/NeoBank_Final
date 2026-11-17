package com.neobank.audit.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.audit.kafka.event.TransactionEvent;
import com.neobank.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuditEventConsumer {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = {
            KafkaTopics.DEBIT_REQUESTED, KafkaTopics.DEBIT_COMPLETED, KafkaTopics.DEBIT_FAILED,
            KafkaTopics.DEBIT_COMPENSATE, KafkaTopics.CREDIT_REQUESTED, KafkaTopics.CREDIT_COMPLETED,
            KafkaTopics.CREDIT_FAILED, KafkaTopics.TRANSACTION_COMPLETED, KafkaTopics.TRANSACTION_FAILED
    }, groupId = "audit-service")
    @SneakyThrows
    public void onAnySagaEvent(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        auditService.record(topic, payload, event);
    }

    /** Account openings and compliance commands (freeze/unfreeze) - not saga steps, but part of the same audit trail. */
    @KafkaListener(topics = {KafkaTopics.ACCOUNT_OPENED, KafkaTopics.COMPLIANCE_ACTION}, groupId = "audit-service")
    @SneakyThrows
    public void onPlatformEvent(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        JsonNode json = objectMapper.readTree(payload);
        auditService.recordGeneric(topic, payload, json);
    }
}
