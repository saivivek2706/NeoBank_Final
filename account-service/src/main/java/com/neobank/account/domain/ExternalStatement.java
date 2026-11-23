package com.neobank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Header of an uploaded external (settlement bank) statement for one date and currency. */
@Entity
@Table(name = "external_statements")
@Setter(AccessLevel.PRIVATE)
public class ExternalStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String source;

    @Column(nullable = false)
    private LocalDate statementDate;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private UUID uploadedBy;

    @Column(nullable = false)
    private Instant uploadedAt;

    @Column(nullable = false)
    private int lineCount;

    /** Public no-argument constructor required by JPA/MongoDB. */
    public ExternalStatement() {
    }

    public UUID getId() {
        return this.id;
    }

    private void setId(UUID id) {
        this.id = id;
    }

    public String getSource() {
        return this.source;
    }

    public LocalDate getStatementDate() {
        return this.statementDate;
    }

    public String getCurrency() {
        return this.currency;
    }

    public UUID getUploadedBy() {
        return this.uploadedBy;
    }

    public Instant getUploadedAt() {
        return this.uploadedAt;
    }

    public int getLineCount() {
        return this.lineCount;
    }

    public static ExternalStatement of(String source, LocalDate statementDate, String currency, UUID uploadedBy,
                                       int lineCount) {
        ExternalStatement s = new ExternalStatement();
        s.setSource(source);
        s.setStatementDate(statementDate);
        s.setCurrency(currency);
        s.setUploadedBy(uploadedBy);
        s.setUploadedAt(Instant.now());
        s.setLineCount(lineCount);
        return s;
    }
}
