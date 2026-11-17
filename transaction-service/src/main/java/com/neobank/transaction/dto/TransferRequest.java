package com.neobank.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be positive") BigDecimal amount,
        @NotNull String currency,
        /** Required only when amount >= transaction.step-up.threshold-amount; see StepUpTokenValidator. */
        String stepUpToken
) {}
