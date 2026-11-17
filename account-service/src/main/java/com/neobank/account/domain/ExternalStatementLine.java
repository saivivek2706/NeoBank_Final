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
import java.util.UUID;

@Entity
@Table(name = "external_statement_lines")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class ExternalStatementLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID statementId;

    @Column(nullable = false, length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MovementDirection direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String description;

    public static ExternalStatementLine of(UUID statementId, String reference, MovementDirection direction,
                                           BigDecimal amount, String currency, String description) {
        ExternalStatementLine line = new ExternalStatementLine();
        line.setStatementId(statementId);
        line.setReference(reference);
        line.setDirection(direction);
        line.setAmount(amount);
        line.setCurrency(currency);
        line.setDescription(description);
        return line;
    }
}
