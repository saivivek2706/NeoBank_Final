package com.neobank.compliance.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.compliance.domain.Direction;
import com.neobank.compliance.kafka.event.AccountOpenedEvent;
import com.neobank.compliance.kafka.event.TransactionEvent;
import com.neobank.compliance.service.MonitoringService;
import com.neobank.compliance.service.ScreeningService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the two compliance functions from the platform's event stream. A malformed payload throws, and the shared error handler
 * retries then dead-letters it (see KafkaErrorHandlingConfig) - a compliance event is never silently dropped.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComplianceEventConsumer {

    private static final String GROUP = "compliance-service";

    private final MonitoringService monitoringService;
    private final ScreeningService screeningService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.ACCOUNT_OPENED, groupId = GROUP)
    @SneakyThrows
    public void onAccountOpened(String payload) {
        screeningService.onAccountOpened(objectMapper.readValue(payload, AccountOpenedEvent.class));
    }

    @KafkaListener(topics = KafkaTopics.DEBIT_COMPLETED, groupId = GROUP)
    @SneakyThrows
    public void onDebitCompleted(String payload) {
        monitoringService.onTransaction(objectMapper.readValue(payload, TransactionEvent.class), Direction.OUT);
    }

    @KafkaListener(topics = KafkaTopics.CREDIT_COMPLETED, groupId = GROUP)
    @SneakyThrows
    public void onCreditCompleted(String payload) {
        monitoringService.onTransaction(objectMapper.readValue(payload, TransactionEvent.class), Direction.IN);
    }
}
