package com.neobank.compliance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "aml_alerts")
@Setter
public class AmlAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 40)
    private String ruleCode;

    @Column(nullable = false)
    private UUID accountId;

    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false, length = 200, unique = true)
    private String dedupKey;

    private UUID caseId;

    @Column(nullable = false)
    private Instant createdAt;

    /** Public no-argument constructor required by JPA/MongoDB. */
    public AmlAlert() {
    }

    public UUID getId() {
        return this.id;
    }

    private void setId(UUID id) {
        this.id = id;
    }

    public String getRuleCode() {
        return this.ruleCode;
    }

    public UUID getAccountId() {
        return this.accountId;
    }

    public UUID getTransactionId() {
        return this.transactionId;
    }

    public Severity getSeverity() {
        return this.severity;
    }

    public int getScore() {
        return this.score;
    }

    public String getSummary() {
        return this.summary;
    }

    public String getDetails() {
        return this.details;
    }

    public String getDedupKey() {
        return this.dedupKey;
    }

    public UUID getCaseId() {
        return this.caseId;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public static AmlAlert of(String ruleCode, UUID accountId, UUID transactionId, Severity severity, int score,
                              String summary, String detailsJson, String dedupKey) {
        AmlAlert a = new AmlAlert();
        a.setRuleCode(ruleCode);
        a.setAccountId(accountId);
        a.setTransactionId(transactionId);
        a.setSeverity(severity);
        a.setScore(score);
        a.setSummary(summary);
        a.setDetails(detailsJson);
        a.setDedupKey(dedupKey);
        a.setCreatedAt(Instant.now());
        return a;
    }

    public void attachTo(UUID caseId) {
        setCaseId(caseId);
    }

}
