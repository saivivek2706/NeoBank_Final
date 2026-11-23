package com.neobank.account.dto;

import com.neobank.account.domain.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateAccountRequest(
        @NotNull UUID ownerId,
        @NotBlank @Size(max = 255) String ownerName,
        @NotNull AccountType accountType,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @DecimalMin(value = "0.0", message = "Opening balance cannot be negative")
        BigDecimal openingBalance,
        /** Settlement-bank reference of the incoming funds backing the opening deposit; what an external statement is matched against. */
        @Size(max = 100) String fundingReference
) {
    public CreateAccountRequest(UUID ownerId, String ownerName, AccountType accountType, String currency,
                                BigDecimal openingBalance) {
        this(ownerId, ownerName, accountType, currency, openingBalance, null);
    }
}
