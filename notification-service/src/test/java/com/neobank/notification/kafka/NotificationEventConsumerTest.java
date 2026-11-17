package com.neobank.notification.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.notification.domain.NotificationType;
import com.neobank.notification.kafka.event.TransactionEvent;
import com.neobank.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationEventConsumerTest {

    @Mock private NotificationService notificationService;

    private NotificationEventConsumer consumer() {
        return new NotificationEventConsumer(notificationService, new ObjectMapper().findAndRegisterModules());
    }

    private String payload(UUID eventId, UUID accountId) {
        return "{\"eventId\":\"" + eventId + "\",\"transactionId\":\"" + UUID.randomUUID()
                + "\",\"accountId\":\"" + accountId + "\",\"amount\":15.00,\"currency\":\"SGD\","
                + "\"reason\":null,\"occurredAt\":\"2026-01-01T00:00:00Z\"}";
    }

    @Test
    void debitCompleted_becomesDebitAlert() {
        UUID eventId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        consumer().onDebitCompleted(payload(eventId, accountId));

        ArgumentCaptor<TransactionEvent> captor = ArgumentCaptor.forClass(TransactionEvent.class);
        verify(notificationService).handle(captor.capture(), org.mockito.ArgumentMatchers.eq(NotificationType.DEBIT_ALERT));
        assertThat(captor.getValue().eventId()).isEqualTo(eventId);
        assertThat(captor.getValue().accountId()).isEqualTo(accountId);
    }

    @Test
    void creditCompleted_becomesCreditAlert() {
        consumer().onCreditCompleted(payload(UUID.randomUUID(), UUID.randomUUID()));

        verify(notificationService).handle(any(), org.mockito.ArgumentMatchers.eq(NotificationType.CREDIT_ALERT));
    }

    @Test
    void transactionFailed_becomesTransferFailedNotification() {
        consumer().onTransactionFailed(payload(UUID.randomUUID(), UUID.randomUUID()));

        verify(notificationService).handle(any(), org.mockito.ArgumentMatchers.eq(NotificationType.TRANSFER_FAILED));
    }

    @Test
    void malformedPayload_isRethrown_soTheErrorHandlerCanDeadLetterIt() {
        assertThatThrownBy(() -> consumer().onDebitCompleted("{not json"))
                .isInstanceOf(JsonProcessingException.class);

        verifyNoInteractions(notificationService);
    }
}
