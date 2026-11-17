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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "aml_alerts")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
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
