package com.neobank.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ReconciliationResponse(
        UUID accountId,
        BigDecimal cachedBalance,
        BigDecimal ledgerDerivedBalance,
        boolean matches
) {
    public static ReconciliationResponse of(UUID accountId, BigDecimal cachedBalance, BigDecimal ledgerDerivedBalance) {
        return new ReconciliationResponse(accountId, cachedBalance, ledgerDerivedBalance,
                cachedBalance.compareTo(ledgerDerivedBalance) == 0);
    }
}
