package com.neobank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One difference between the external statement and the ledger. Stays open until someone resolves it with a note. */
@Entity
@Table(name = "reconciliation_breaks")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class ReconciliationBreak {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private BreakType breakType;

    @Column(length = 100)
    private String reference;

    @Column(precision = 19, scale = 4)
    private BigDecimal statementAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal ledgerAmount;

    @Column(nullable = false, length = 500)
    private String detail;

    @Column(nullable = false)
    private boolean resolved;

    private UUID resolvedBy;

    private Instant resolvedAt;

    @Column(length = 500)
    private String resolutionNote;

    public static ReconciliationBreak of(UUID runId, BreakType type, String reference, BigDecimal statementAmount,
                                         BigDecimal ledgerAmount, String detail) {
        ReconciliationBreak b = new ReconciliationBreak();
        b.setRunId(runId);
        b.setBreakType(type);
        b.setReference(reference);
        b.setStatementAmount(statementAmount);
        b.setLedgerAmount(ledgerAmount);
        b.setDetail(detail);
        return b;
    }

    public void resolve(UUID by, String note) {
        if (resolved) {
            throw new IllegalStateException("Break is already resolved");
        }
        setResolved(true);
        setResolvedBy(by);
        setResolvedAt(Instant.now());
        setResolutionNote(note);
    }
}
