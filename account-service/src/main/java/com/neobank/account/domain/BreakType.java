package com.neobank.account.domain;

public enum BreakType {
    /** The bank's statement shows a movement the ledger has no record of. */
    MISSING_IN_LEDGER,
    /** The ledger recorded a movement the bank's statement does not show. */
    MISSING_IN_STATEMENT,
    AMOUNT_MISMATCH,
    DIRECTION_MISMATCH,
    CURRENCY_MISMATCH,
    DUPLICATE_REFERENCE_IN_STATEMENT,
    DUPLICATE_REFERENCE_IN_LEDGER
}
