package com.neobank.account.dto;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountStatus;
import com.neobank.account.domain.AccountType;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        String ownerName,
        BigDecimal balance,
        String currency,
        AccountStatus status,
        AccountType accountType
) {
    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getAccountNumber(), a.getOwnerName(),
                a.getBalance(), a.getCurrency(), a.getStatus(), a.getAccountType());
    }

    /** Same shape, masked account number - see Account.getMaskedAccountNumber() for why list views use this and detail views don't. */
    public static AccountResponse fromMasked(Account a) {
        return new AccountResponse(a.getId(), a.getMaskedAccountNumber(), a.getOwnerName(),
                a.getBalance(), a.getCurrency(), a.getStatus(), a.getAccountType());
    }
}
