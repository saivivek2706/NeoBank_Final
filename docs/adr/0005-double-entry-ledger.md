# ADR 0005: Double-entry journal as the source of truth

**Status:** accepted

## Context
The first design kept a cached `balance` on each account plus one single-sided `ledger_entries` line per balance
change. That shows a customer's history but cannot prove the bank's books are right: nothing says where money
physically is while a transfer is in flight, fees and external money have nowhere to be recorded, and a bug that
changed a balance and a statement line differently would go unnoticed.

## Decision
- Every money movement is a **journal entry** of **postings**; for each journal, debits equal credits - enforced by a
  deferred Postgres trigger (checked at COMMIT), so even code that bypasses the application layer cannot save an
  unbalanced journal. `journal_entries` and `postings` are **append-only** (UPDATE/DELETE are rejected by trigger);
  mistakes are corrected with a *reversing journal*.
- Customer accounts are liabilities of the bank; system accounts (`SETTLEMENT`, `FEE_INCOME`, `EXTERNAL_FUNDING`,
  `LEGACY_CLEARING`) exist per currency. A transfer's debit leg credits `SETTLEMENT` and its credit leg debits it,
  so `SETTLEMENT` is exactly "money in flight" and must return to zero.
- A unique index on `(transaction_id, journal_type)` for the transfer steps and on `reverses_journal_id` makes it
  impossible to debit, credit or reverse a transfer twice.
- `accounts.balance` stays as a cache (row-locked on the saga path) and `ledger_entries` as the statement view, both
  written in the same transaction as the journal. `LedgerAuditService` proves cache == postings and the trial balance
  nets to zero; a lingering `SETTLEMENT` balance means a stuck transfer.
- Existing history is migrated into balanced `LEGACY_BACKFILL` journals against `LEGACY_CLEARING`.
- `EXTERNAL_FUNDING` (cash at the settlement bank) is the account reconciled against the bank's daily statement.

## Consequences
+ The books can be proven correct at any time, and money in flight, fees and external cash are all visible.
+ Compensation is an exact mirror of the original debit (fee included), not a hand-computed number.
- More writes per transfer (journal + postings alongside the statement line) and more tables to keep consistent.
- Customer balances still exist twice (cache and postings); the integrity audit is what keeps that honest.
