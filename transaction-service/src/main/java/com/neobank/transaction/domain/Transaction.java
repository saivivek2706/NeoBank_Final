package com.neobank.transaction.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID fromAccountId;

    @Column(nullable = false)
    private UUID toAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    /** Authenticated user who started the transfer (JWT sub). Null only for rows created before V2. */
    private UUID requestedBy;

    /** Client-supplied Idempotency-Key, unique per requestedBy. See V2__idempotency_keys.sql. */
    @Column(length = 100)
    private String idempotencyKey;

    /** SHA-256 of the request's business fields - detects a key reused for a different transfer. */
    @Column(length = 64)
    private String requestFingerprint;

    public static Transaction initiate(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String currency,
                                       UUID requestedBy, String idempotencyKey, String requestFingerprint) {
        Transaction tx = initiate(fromAccountId, toAccountId, amount, currency);
        tx.setRequestedBy(requestedBy);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRequestFingerprint(requestFingerprint);
        return tx;
    }

    public static Transaction initiate(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String currency) {
        Transaction tx = new Transaction();
        tx.setFromAccountId(fromAccountId);
        tx.setToAccountId(toAccountId);
        tx.setAmount(amount);
        tx.setCurrency(currency);
        tx.setStatus(TransactionStatus.INITIATED);
        tx.setCreatedAt(Instant.now());
        return tx;
    }

    public void moveTo(TransactionStatus newStatus) {
        setStatus(newStatus);
        setUpdatedAt(Instant.now());
    }

    public void fail(String reason) {
        setStatus(TransactionStatus.FAILED);
        setFailureReason(reason);
        setUpdatedAt(Instant.now());
    }
}
