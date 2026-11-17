package com.neobank.compliance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "monitored_transactions")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class MonitoredTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false)
    private UUID transactionId;

    @Column(nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Direction direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant occurredAt;

    public static MonitoredTransaction of(UUID eventId, UUID transactionId, UUID accountId, Direction direction,
                                          BigDecimal amount, String currency, Instant occurredAt) {
        MonitoredTransaction t = new MonitoredTransaction();
        t.setEventId(eventId);
        t.setTransactionId(transactionId);
        t.setAccountId(accountId);
        t.setDirection(direction);
        t.setAmount(amount);
        t.setCurrency(currency);
        t.setOccurredAt(occurredAt);
        return t;
    }

}
