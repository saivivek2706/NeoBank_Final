CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    account_number  VARCHAR(20)     NOT NULL UNIQUE,
    owner_id        UUID            NOT NULL,
    owner_name      VARCHAR(255)    NOT NULL,
    balance         NUMERIC(19,4)   NOT NULL DEFAULT 0,
    currency        VARCHAR(3)      NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    account_type    VARCHAR(20)     NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_accounts_owner_id ON accounts (owner_id);

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
