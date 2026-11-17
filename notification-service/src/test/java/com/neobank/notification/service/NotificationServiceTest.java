package com.neobank.notification.service;

import com.neobank.notification.domain.Notification;
import com.neobank.notification.domain.NotificationType;
import com.neobank.notification.domain.ProcessedEvent;
import com.neobank.notification.kafka.event.TransactionEvent;
import com.neobank.notification.repository.NotificationRepository;
import com.neobank.notification.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private ProcessedEventRepository processedEventRepository;
    @InjectMocks private NotificationService notificationService;

    private TransactionEvent event() {
        return new TransactionEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("42.50"), "SGD", null, Instant.now());
    }

    @Test
    void handle_persistsNotificationAndMarksEventProcessed() {
        TransactionEvent event = event();
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        notificationService.handle(event, NotificationType.DEBIT_ALERT);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(NotificationType.DEBIT_ALERT);
        assertThat(captor.getValue().getAccountId()).isEqualTo(event.accountId());
        assertThat(captor.getValue().getMessage()).contains("debited").contains("SGD").contains("42.50");
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void handle_skipsEventsAlreadyProcessed_soRedeliveryNeverNotifiesTwice() {
        TransactionEvent event = event();
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        notificationService.handle(event, NotificationType.CREDIT_ALERT);

        verify(notificationRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void messageWording_matchesTheNotificationType() {
        UUID id = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");

        assertThat(Notification.of(id, id, NotificationType.CREDIT_ALERT, amount, "SGD").getMessage()).contains("credited");
        assertThat(Notification.of(id, id, NotificationType.TRANSFER_FAILED, amount, "SGD").getMessage())
                .contains("could not be completed");
    }
}
