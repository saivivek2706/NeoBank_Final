CREATE TABLE notifications (
    id              UUID PRIMARY KEY,
    transaction_id  UUID            NOT NULL,
    account_id      UUID            NOT NULL,
    type            VARCHAR(20)     NOT NULL,
    message         TEXT            NOT NULL,
    sent_at         TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_account ON notifications (account_id, sent_at DESC);

CREATE TABLE processed_events (
    event_id        UUID PRIMARY KEY,
    processed_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);
