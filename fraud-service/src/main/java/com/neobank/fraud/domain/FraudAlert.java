package com.neobank.fraud.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_alerts")
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

    /** Public no-argument constructor required by JPA/MongoDB. */
    public FraudAlert() {
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

    public BigDecimal getAmount() {
        return this.amount;
    }

    public FraudRuleType getRuleTriggered() {
        return this.ruleTriggered;
    }

    public FraudSeverity getSeverity() {
        return this.severity;
    }

    public String getDetails() {
        return this.details;
    }

    public Instant getFlaggedAt() {
        return this.flaggedAt;
    }

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
