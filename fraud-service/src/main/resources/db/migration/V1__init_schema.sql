CREATE TABLE fraud_alerts (
    id                  UUID PRIMARY KEY,
    transaction_id      UUID            NOT NULL,
    account_id          UUID            NOT NULL,
    amount              NUMERIC(19,4)   NOT NULL,
    rule_triggered      VARCHAR(30)     NOT NULL,
    severity            VARCHAR(20)     NOT NULL,
    details             TEXT            NOT NULL,
    flagged_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_fraud_account ON fraud_alerts (account_id, flagged_at DESC);
CREATE INDEX idx_fraud_flagged_at ON fraud_alerts (flagged_at DESC);

CREATE TABLE processed_events (
    event_id        UUID PRIMARY KEY,
    processed_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);
