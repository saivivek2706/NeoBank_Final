package com.neobank.account.dto;

import com.neobank.account.domain.LedgerEntry;
import com.neobank.account.domain.LedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryResponse(
        UUID id,
        UUID transactionId,
        LedgerEntryType entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String description,
        Instant createdAt
) {
    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(e.getId(), e.getTransactionId(), e.getEntryType(),
                e.getAmount(), e.getBalanceAfter(), e.getDescription(), e.getCreatedAt());
    }
}
