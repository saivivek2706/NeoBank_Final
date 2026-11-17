package com.neobank.notification.dto;

import com.neobank.notification.domain.Notification;
import com.neobank.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID transactionId,
        UUID accountId,
        NotificationType type,
        String message,
        Instant sentAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getTransactionId(), n.getAccountId(),
                n.getType(), n.getMessage(), n.getSentAt());
    }
}
