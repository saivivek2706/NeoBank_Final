CREATE TABLE transactions (
    id                  UUID PRIMARY KEY,
    from_account_id     UUID            NOT NULL,
    to_account_id       UUID            NOT NULL,
    amount              NUMERIC(19,4)   NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    status              VARCHAR(20)     NOT NULL,
    failure_reason      VARCHAR(255),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ
);

CREATE INDEX idx_tx_from_account ON transactions (from_account_id);
CREATE INDEX idx_tx_to_account ON transactions (to_account_id);
CREATE INDEX idx_tx_status ON transactions (status);

CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(100)    NOT NULL,
    aggregate_id    VARCHAR(100)    NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    topic           VARCHAR(150)    NOT NULL,
    payload         TEXT            NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    attempts        INT             NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);

CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);

CREATE TABLE processed_events (
    event_id        UUID PRIMARY KEY,
    processed_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);
