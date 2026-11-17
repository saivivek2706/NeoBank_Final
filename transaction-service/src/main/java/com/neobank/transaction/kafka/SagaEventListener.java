package com.neobank.transaction.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.transaction.kafka.event.TransactionEvent;
import com.neobank.transaction.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaEventListener {

    private final TransferService transferService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.DEBIT_COMPLETED, groupId = "transaction-service")
    @SneakyThrows
    public void onDebitCompleted(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        log.info("debit.completed for transaction={}", event.transactionId());
        transferService.onDebitCompleted(event);
    }

    @KafkaListener(topics = KafkaTopics.DEBIT_FAILED, groupId = "transaction-service")
    @SneakyThrows
    public void onDebitFailed(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        log.warn("debit.failed for transaction={} reason={}", event.transactionId(), event.reason());
        transferService.onDebitFailed(event);
    }

    @KafkaListener(topics = KafkaTopics.CREDIT_COMPLETED, groupId = "transaction-service")
    @SneakyThrows
    public void onCreditCompleted(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        log.info("credit.completed for transaction={}", event.transactionId());
        transferService.onCreditCompleted(event);
    }

    @KafkaListener(topics = KafkaTopics.CREDIT_FAILED, groupId = "transaction-service")
    @SneakyThrows
    public void onCreditFailed(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        log.warn("credit.failed for transaction={} reason={} - triggering compensation", event.transactionId(), event.reason());
        transferService.onCreditFailed(event);
    }
}
