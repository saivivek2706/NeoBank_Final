package com.neobank.notification.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.notification.domain.NotificationType;
import com.neobank.notification.kafka.event.TransactionEvent;
import com.neobank.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.DEBIT_COMPLETED, groupId = "notification-service")
    @SneakyThrows
    public void onDebitCompleted(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        notificationService.handle(event, NotificationType.DEBIT_ALERT);
    }

    @KafkaListener(topics = KafkaTopics.CREDIT_COMPLETED, groupId = "notification-service")
    @SneakyThrows
    public void onCreditCompleted(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        notificationService.handle(event, NotificationType.CREDIT_ALERT);
    }

    @KafkaListener(topics = KafkaTopics.TRANSACTION_FAILED, groupId = "notification-service")
    @SneakyThrows
    public void onTransactionFailed(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        notificationService.handle(event, NotificationType.TRANSFER_FAILED);
    }
}
