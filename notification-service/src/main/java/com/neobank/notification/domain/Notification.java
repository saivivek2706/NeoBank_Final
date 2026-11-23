package com.neobank.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A simulated notification. In place of an actual SMTP/SMS provider (Twilio,
 * SES, etc.) - which would need real credentials and a real domain to be
 * useful in a portfolio demo - this service persists what WOULD have been
 * sent, and logs it, so you can show the "notification fired the instant the
 * transaction completed" behavior without depending on external infra.
 * Swapping in a real provider is a single class behind the same interface.
 */
@Entity
@Table(name = "notifications")
@Setter(AccessLevel.PRIVATE)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID transactionId;

    @Column(nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    @Column(nullable = false)
    private String message;

    @Column(nullable = false)
    private Instant sentAt;

    /** Public no-argument constructor required by JPA/MongoDB. */
    public Notification() {
    }

    public UUID getId() {
        return this.id;
    }

    private void setId(UUID id) {
        this.id = id;
    }

    public UUID getTransactionId() {
        return this.transactionId;
    }

    public UUID getAccountId() {
        return this.accountId;
    }

    public NotificationType getType() {
        return this.type;
    }

    public String getMessage() {
        return this.message;
    }

    public Instant getSentAt() {
        return this.sentAt;
    }

    public static Notification of(UUID transactionId, UUID accountId, NotificationType type,
                                   BigDecimal amount, String currency) {
        Notification n = new Notification();
        n.setTransactionId(transactionId);
        n.setAccountId(accountId);
        n.setType(type);
        n.setMessage(buildMessage(type, amount, currency));
        n.setSentAt(Instant.now());
        return n;
    }

    private static String buildMessage(NotificationType type, BigDecimal amount, String currency) {
        return switch (type) {
            case DEBIT_ALERT -> "Your account was debited " + currency + " " + amount + ".";
            case CREDIT_ALERT -> "Your account was credited " + currency + " " + amount + ".";
            case TRANSFER_FAILED -> "A transfer of " + currency + " " + amount + " could not be completed.";
        };
    }
}
