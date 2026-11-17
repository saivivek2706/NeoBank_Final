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
 * A reservation on part of an account's balance. While ACTIVE (and not past expiresAt) the amount is excluded from
 * the account's available balance, so it cannot be spent by transfers - but no money has moved and no journal exists
 * until the hold is captured.
 */
@Entity
@Table(name = "holds")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class Hold {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(precision = 19, scale = 4)
    private BigDecimal capturedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HoldStatus status;

    @Column(nullable = false, length = 100)
    private String reference;

    @Column(length = 255)
    private String reason;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant resolvedAt;

    private UUID captureJournalId;

    public static Hold place(UUID accountId, BigDecimal amount, String currency, String reference, String reason,
                             Instant expiresAt) {
        Hold hold = new Hold();
        hold.setAccountId(accountId);
        hold.setAmount(amount);
        hold.setCurrency(currency);
        hold.setStatus(HoldStatus.ACTIVE);
        hold.setReference(reference);
        hold.setReason(reason);
        hold.setExpiresAt(expiresAt);
        hold.setCreatedAt(Instant.now());
        return hold;
    }

    public boolean isActiveAt(Instant now) {
        return status == HoldStatus.ACTIVE && expiresAt.isAfter(now);
    }

    public void release() {
        setStatus(HoldStatus.RELEASED);
        setResolvedAt(Instant.now());
    }

    public void capture(BigDecimal capturedAmount, UUID journalId) {
        setStatus(HoldStatus.CAPTURED);
        setCapturedAmount(capturedAmount);
        setCaptureJournalId(journalId);
        setResolvedAt(Instant.now());
    }
}
