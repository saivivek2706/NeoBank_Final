package com.neobank.account.domain;

public enum JournalType {
    OPENING_DEPOSIT,
    TRANSFER_DEBIT,
    TRANSFER_CREDIT,
    TRANSFER_REVERSAL,
    HOLD_CAPTURE,
    LEGACY_BACKFILL
}
