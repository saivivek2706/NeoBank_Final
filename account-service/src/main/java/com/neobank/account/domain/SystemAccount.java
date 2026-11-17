package com.neobank.account.domain;

/**
 * The bank's own (system) ledger accounts, created per currency on first use.
 *
 *   SETTLEMENT        money in flight between the debit and credit legs of a transfer. Credited when the sender is
 *                     debited, debited when the receiver is credited (or the debit is reversed). Its balance is
 *                     therefore "money currently mid-transfer" and must return to zero once nothing is in flight -
 *                     a lingering balance means a stuck transfer.
 *   FEE_INCOME        fees charged to customers (the bank's revenue).
 *   EXTERNAL_FUNDING  cash held at the settlement bank. Debited when money comes in from outside the bank,
 *                     credited when it is paid out. This is the account an external bank statement is reconciled to.
 *   LEGACY_CLEARING   contra-account for history that pre-dates the double-entry journal (migration V4).
 */
public enum SystemAccount {
    SETTLEMENT("Settlement (transfers in flight)", GlAccountType.LIABILITY),
    FEE_INCOME("Fee income", GlAccountType.INCOME),
    EXTERNAL_FUNDING("External funding (cash at settlement bank)", GlAccountType.ASSET),
    LEGACY_CLEARING("Legacy balance clearing", GlAccountType.LIABILITY);

    private final String displayName;
    private final GlAccountType type;

    SystemAccount(String displayName, GlAccountType type) {
        this.displayName = displayName;
        this.type = type;
    }

    public String displayName() {
        return displayName;
    }

    public GlAccountType type() {
        return type;
    }
}
