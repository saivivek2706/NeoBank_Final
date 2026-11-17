-- Reconciliation of the bank's own books against an EXTERNAL statement (the settlement bank's daily file).
-- A statement is uploaded once per (source, date, currency); each upload is matched against the ledger's
-- EXTERNAL_FUNDING movements for that date and every difference becomes a BREAK that a human must resolve.
CREATE TABLE external_statements (
    id              UUID PRIMARY KEY,
    source          VARCHAR(50)  NOT NULL,
    statement_date  DATE         NOT NULL,
    currency        VARCHAR(3)   NOT NULL,
    uploaded_by     UUID         NOT NULL,
    uploaded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    line_count      INT          NOT NULL,
    CONSTRAINT uq_external_statement UNIQUE (source, statement_date, currency)
);

CREATE TABLE external_statement_lines (
    id            UUID PRIMARY KEY,
    statement_id  UUID          NOT NULL REFERENCES external_statements(id),
    reference     VARCHAR(100)  NOT NULL,
    direction     VARCHAR(10)   NOT NULL CHECK (direction IN ('INFLOW', 'OUTFLOW')),  -- from the bank's point of view
    amount        NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency      VARCHAR(3)    NOT NULL,
    description   VARCHAR(255)
);

CREATE INDEX idx_statement_lines_statement ON external_statement_lines (statement_id);

CREATE TABLE reconciliation_runs (
    id                UUID PRIMARY KEY,
    statement_id      UUID         NOT NULL REFERENCES external_statements(id),
    run_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    statement_lines   INT          NOT NULL,
    ledger_movements  INT          NOT NULL,
    matched           INT          NOT NULL,
    break_count       INT          NOT NULL,
    status            VARCHAR(20)  NOT NULL               -- CLEAN | BREAKS
);

CREATE TABLE reconciliation_breaks (
    id                UUID PRIMARY KEY,
    run_id            UUID          NOT NULL REFERENCES reconciliation_runs(id),
    break_type        VARCHAR(40)   NOT NULL,
    reference         VARCHAR(100),
    statement_amount  NUMERIC(19,4),
    ledger_amount     NUMERIC(19,4),
    detail            VARCHAR(500)  NOT NULL,
    resolved          BOOLEAN       NOT NULL DEFAULT FALSE,
    resolved_by       UUID,
    resolved_at       TIMESTAMPTZ,
    resolution_note   VARCHAR(500)
);

CREATE INDEX idx_recon_breaks_run ON reconciliation_breaks (run_id);
CREATE INDEX idx_recon_breaks_open ON reconciliation_breaks (resolved) WHERE resolved = FALSE;
