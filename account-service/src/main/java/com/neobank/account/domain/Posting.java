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

/**
 * One debit or credit against exactly one account - either a customer account or a system GL account. Amount is
 * always positive; the side comes from {@code direction} (same convention as ledger_entries).
 *
 * For a customer account (a liability of the bank) CREDIT increases the balance and DEBIT decreases it.
 */
@Entity
@Table(name = "postings")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID journalId;

    private UUID customerAccountId;

    private UUID glAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private PostingDirection direction;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    public static Posting customer(UUID journalId, UUID customerAccountId, PostingDirection direction,
                                   BigDecimal amount, String currency) {
        Posting p = base(journalId, direction, amount, currency);
        p.setCustomerAccountId(customerAccountId);
        return p;
    }

    public static Posting gl(UUID journalId, UUID glAccountId, PostingDirection direction,
                             BigDecimal amount, String currency) {
        Posting p = base(journalId, direction, amount, currency);
        p.setGlAccountId(glAccountId);
        return p;
    }

    private static Posting base(UUID journalId, PostingDirection direction, BigDecimal amount, String currency) {
        Posting p = new Posting();
        p.setJournalId(journalId);
        p.setDirection(direction);
        p.setAmount(amount);
        p.setCurrency(currency);
        p.setCreatedAt(Instant.now());
        return p;
    }
}
