package com.neobank.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin(value = "0.01", message = "Amount must be positive") @Digits(integer = 15, fraction = 4, message = "Amount must fit NUMERIC(19,4)") BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter uppercase ISO code") String currency,
        /** Required only when amount >= transaction.step-up.threshold-amount; see StepUpTokenValidator. */
        String stepUpToken
) {}
