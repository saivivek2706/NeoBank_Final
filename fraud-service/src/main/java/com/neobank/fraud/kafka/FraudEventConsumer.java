package com.neobank.fraud.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.fraud.kafka.event.TransactionEvent;
import com.neobank.fraud.service.FraudDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FraudEventConsumer {

    private final FraudDetectionService fraudDetectionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.DEBIT_COMPLETED, groupId = "fraud-service")
    @SneakyThrows
    public void onDebitCompleted(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        fraudDetectionService.screen(event);
    }
}
