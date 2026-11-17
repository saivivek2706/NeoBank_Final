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
import java.time.LocalDate;
import java.util.UUID;

/** Header of an uploaded external (settlement bank) statement for one date and currency. */
@Entity
@Table(name = "external_statements")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
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
