package com.neobank.account.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The authoritative, append-only record of every balance movement.
 * Account.balance is still kept as a fast-read cached column (mutated
 * directly by Account.debit()/credit()/reverseDebit()), but this table is
 * the source of truth for "what actually happened and when" - it is never
 * updated or deleted after being written, only ever appended to, exactly
 * like a real bank's general ledger.
 *
 * Every entry created inside handleDebitRequested/handleCreditRequested/
 * handleDebitCompensate is written in the SAME database transaction as the
 * Account.balance mutation it corresponds to (see AccountService), so the
 * cached balance and the ledger can never drift from a partial write - only
 * from a genuine bug, which is exactly what LedgerReconciliationService
 * exists to catch.
 *
 * `amount` is always positive; direction comes from `entryType` alone
 * (DEBIT = money left this account, CREDIT = money entered it) - this is
 * the standard convention that avoids sign-error bugs from "negative debit"
 * style modeling.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID accountId;

    /** Correlates the two entries (one per side) that make up a single transfer - see transaction-service's Transaction.id. */
    @Column(nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LedgerEntryType entryType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Snapshot of the account's balance immediately after this entry - lets you reconstruct history without replaying every prior entry. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(nullable = false)
    private Instant createdAt;

    /** The double-entry journal this statement line belongs to (see JournalEntry). */
    private UUID journalId;

    public static LedgerEntry of(UUID accountId, UUID transactionId, LedgerEntryType type,
                                  BigDecimal amount, BigDecimal balanceAfter, String description, UUID journalId) {
        LedgerEntry entry = of(accountId, transactionId, type, amount, balanceAfter, description);
        entry.setJournalId(journalId);
        return entry;
    }

    public static LedgerEntry of(UUID accountId, UUID transactionId, LedgerEntryType type,
                                  BigDecimal amount, BigDecimal balanceAfter, String description) {
        LedgerEntry entry = new LedgerEntry();
        entry.setAccountId(accountId);
        entry.setTransactionId(transactionId);
        entry.setEntryType(type);
        entry.setAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        entry.setDescription(description);
        entry.setCreatedAt(Instant.now());
        return entry;
    }
}
