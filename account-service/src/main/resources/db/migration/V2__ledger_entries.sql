CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY,
    account_id     UUID NOT NULL REFERENCES accounts(id),
    transaction_id UUID NOT NULL,
    entry_type     VARCHAR(10) NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    balance_after  NUMERIC(19,4) NOT NULL,
    description    VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);

-- Backs "list this account's history" (paginated, newest first).
CREATE INDEX idx_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC);

-- Backs "find both sides of this transfer" for support/fraud investigation.
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
