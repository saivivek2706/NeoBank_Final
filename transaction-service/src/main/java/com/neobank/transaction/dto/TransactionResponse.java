package com.neobank.transaction.dto;

import com.neobank.transaction.domain.Transaction;
import com.neobank.transaction.domain.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        String failureReason,
        Instant createdAt
) {
    public static TransactionResponse from(Transaction tx) {
        return new TransactionResponse(tx.getId(), tx.getFromAccountId(), tx.getToAccountId(),
                tx.getAmount(), tx.getCurrency(), tx.getStatus(), tx.getFailureReason(), tx.getCreatedAt());
    }
}
