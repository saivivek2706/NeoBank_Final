package com.neobank.fraud.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_alerts")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class FraudAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID transactionId;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FraudRuleType ruleTriggered;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FraudSeverity severity;

    @Column(nullable = false)
    private String details;

    @Column(nullable = false)
    private Instant flaggedAt;

    public static FraudAlert of(UUID transactionId, UUID accountId, BigDecimal amount,
                                 FraudRuleType rule, FraudSeverity severity, String details) {
        FraudAlert alert = new FraudAlert();
        alert.setTransactionId(transactionId);
        alert.setAccountId(accountId);
        alert.setAmount(amount);
        alert.setRuleTriggered(rule);
        alert.setSeverity(severity);
        alert.setDetails(details);
        alert.setFlaggedAt(Instant.now());
        return alert;
    }
}
