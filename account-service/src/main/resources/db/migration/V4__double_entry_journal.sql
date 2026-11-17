-- Double-entry accounting core.
--
-- Until now each transfer wrote one single-sided line per customer account (ledger_entries). That can show a
-- customer's history but cannot prove the bank's books balance or where money physically is. This migration adds a
-- proper double-entry journal: every business event is a JOURNAL ENTRY made of POSTINGS, and for every journal
-- the debits must equal the credits - enforced by the database, not just by application code.
--
-- Accounts that can be posted to:
--   * customer accounts (the existing `accounts` rows) - LIABILITIES of the bank, credit-normal
--   * system "GL" accounts (gl_accounts): SETTLEMENT (money in flight between the debit and credit steps of a
--     transfer), FEE_INCOME, EXTERNAL_FUNDING (cash at the settlement bank) and LEGACY_CLEARING.
--
-- ledger_entries is kept as the per-account STATEMENT view (it carries balance_after) and now links to its journal.

CREATE TABLE gl_accounts (
    id            UUID PRIMARY KEY,
    code          VARCHAR(40)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    account_type  VARCHAR(20)  NOT NULL,                 -- ASSET | LIABILITY | INCOME
    currency      VARCHAR(3)   NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_gl_account_code_currency UNIQUE (code, currency)
);

CREATE TABLE journal_entries (
    id                   UUID PRIMARY KEY,
    transaction_id       UUID         NOT NULL,           -- the transfer saga id (or a synthetic id for deposits/holds)
    journal_type         VARCHAR(30)  NOT NULL,
    description          VARCHAR(255) NOT NULL,
    currency             VARCHAR(3)   NOT NULL,
    external_reference   VARCHAR(100),                    -- bank reference for movements of money in/out of the bank
    reverses_journal_id  UUID REFERENCES journal_entries(id),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A transfer can have at most one debit journal, one credit journal and one reversal: a replayed saga event can
-- never post twice, even if an application-level idempotency check were bypassed.
CREATE UNIQUE INDEX uq_journal_transfer_step
    ON journal_entries (transaction_id, journal_type)
    WHERE journal_type IN ('TRANSFER_DEBIT', 'TRANSFER_CREDIT', 'TRANSFER_REVERSAL');

-- A journal can be reversed at most once.
CREATE UNIQUE INDEX uq_journal_reversed_once
    ON journal_entries (reverses_journal_id)
    WHERE reverses_journal_id IS NOT NULL;

CREATE INDEX idx_journal_transaction ON journal_entries (transaction_id);
CREATE INDEX idx_journal_external_reference ON journal_entries (external_reference) WHERE external_reference IS NOT NULL;

CREATE TABLE postings (
    id                   UUID PRIMARY KEY,
    journal_id           UUID          NOT NULL REFERENCES journal_entries(id),
    customer_account_id  UUID          REFERENCES accounts(id),
    gl_account_id        UUID          REFERENCES gl_accounts(id),
    direction            VARCHAR(6)    NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount               NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency             VARCHAR(3)    NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- exactly one of the two account columns is set
    CONSTRAINT ck_posting_one_account CHECK ((customer_account_id IS NULL) <> (gl_account_id IS NULL))
);

CREATE INDEX idx_postings_journal ON postings (journal_id);
CREATE INDEX idx_postings_customer_account ON postings (customer_account_id) WHERE customer_account_id IS NOT NULL;
CREATE INDEX idx_postings_gl_account ON postings (gl_account_id, created_at) WHERE gl_account_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------
-- Backfill: every pre-existing single-sided ledger entry becomes a balanced journal against a LEGACY_CLEARING
-- account, so customer balances derived from postings equal the cached balances from day one.
-- (The journal reuses the ledger entry's id, which keeps the link trivial.)
-- ---------------------------------------------------------------------------------------------------------------
INSERT INTO gl_accounts (id, code, name, account_type, currency)
SELECT gen_random_uuid(), 'LEGACY_CLEARING', 'Legacy balance clearing (history before double-entry)', 'LIABILITY', c.currency
FROM (SELECT DISTINCT currency FROM accounts) c;

INSERT INTO journal_entries (id, transaction_id, journal_type, description, currency, created_at)
SELECT le.id, le.transaction_id, 'LEGACY_BACKFILL', le.description, a.currency, le.created_at
FROM ledger_entries le JOIN accounts a ON a.id = le.account_id;

INSERT INTO postings (id, journal_id, customer_account_id, gl_account_id, direction, amount, currency, created_at)
SELECT gen_random_uuid(), le.id, le.account_id, NULL, le.entry_type, le.amount, a.currency, le.created_at
FROM ledger_entries le JOIN accounts a ON a.id = le.account_id;

INSERT INTO postings (id, journal_id, customer_account_id, gl_account_id, direction, amount, currency, created_at)
SELECT gen_random_uuid(), le.id, NULL, g.id,
       CASE WHEN le.entry_type = 'CREDIT' THEN 'DEBIT' ELSE 'CREDIT' END,
       le.amount, a.currency, le.created_at
FROM ledger_entries le
         JOIN accounts a ON a.id = le.account_id
         JOIN gl_accounts g ON g.code = 'LEGACY_CLEARING' AND g.currency = a.currency;

ALTER TABLE ledger_entries ADD COLUMN journal_id UUID;
UPDATE ledger_entries SET journal_id = id;
ALTER TABLE ledger_entries ADD CONSTRAINT fk_ledger_entries_journal FOREIGN KEY (journal_id) REFERENCES journal_entries(id);
CREATE INDEX idx_ledger_entries_journal ON ledger_entries (journal_id);

-- ---------------------------------------------------------------------------------------------------------------
-- Integrity guards (created after the backfill).
-- ---------------------------------------------------------------------------------------------------------------

-- 1. A journal must balance: checked at COMMIT (deferred), once all of the journal's postings are inserted.
CREATE FUNCTION check_journal_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    total_debit  NUMERIC;
    total_credit NUMERIC;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO total_debit, total_credit
      FROM postings WHERE journal_id = NEW.journal_id;
    IF total_debit <> total_credit THEN
        RAISE EXCEPTION 'Journal % is unbalanced: debits=% credits=%', NEW.journal_id, total_debit, total_credit
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_journal_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();

-- 2. The journal is append-only: corrections are made with a reversing journal, never an UPDATE or DELETE.
CREATE FUNCTION forbid_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: the ledger is append-only (post a reversing journal instead)', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END $$;

CREATE TRIGGER trg_postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();

CREATE TRIGGER trg_journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();
