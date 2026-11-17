package com.neobank.notification.service;

import com.neobank.notification.domain.Notification;
import com.neobank.notification.domain.NotificationType;
import com.neobank.notification.domain.ProcessedEvent;
import com.neobank.notification.kafka.event.TransactionEvent;
import com.neobank.notification.repository.NotificationRepository;
import com.neobank.notification.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void handle(TransactionEvent event, NotificationType type) {
        if (processedEventRepository.existsById(event.eventId())) {
            return; // already notified for this exact event - Kafka redelivery, skip
        }
        Notification notification = Notification.of(event.transactionId(), event.accountId(),
                type, event.amount(), event.currency());
        notificationRepository.save(notification);
        processedEventRepository.save(new ProcessedEvent(event.eventId()));

        // Simulated send - in production this line is replaced by a call to an
        // email/SMS provider SDK (SES, Twilio, etc.). The point being demonstrated
        // here is the event-driven trigger, not the delivery mechanism itself.
        log.info("[SIMULATED NOTIFICATION] account={} :: {}", event.accountId(), notification.getMessage());
    }
}
