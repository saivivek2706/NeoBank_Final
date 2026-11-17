package com.neobank.account.domain;

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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID statementId;

    @Column(nullable = false)
    private Instant runAt;

    @Column(nullable = false)
    private int statementLines;

    @Column(nullable = false)
    private int ledgerMovements;

    @Column(nullable = false)
    private int matched;

    @Column(nullable = false)
    private int breakCount;

    /** CLEAN when everything matched, BREAKS otherwise. */
    @Column(nullable = false, length = 20)
    private String status;

    public static ReconciliationRun of(UUID statementId, int statementLines, int ledgerMovements, int matched,
                                       int breakCount) {
        ReconciliationRun run = new ReconciliationRun();
        run.setStatementId(statementId);
        run.setRunAt(Instant.now());
        run.setStatementLines(statementLines);
        run.setLedgerMovements(ledgerMovements);
        run.setMatched(matched);
        run.setBreakCount(breakCount);
        run.setStatus(breakCount == 0 ? "CLEAN" : "BREAKS");
        return run;
    }
}
