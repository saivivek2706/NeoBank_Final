-- Authorization holds. A hold reserves part of a customer's balance without moving it (card-style authorizations,
-- pending payments, deposit-protection). available balance = balance - sum(ACTIVE, unexpired holds).
-- Capturing a hold posts the real money movement; releasing or expiring it just frees the reservation.
CREATE TABLE holds (
    id                  UUID PRIMARY KEY,
    account_id          UUID          NOT NULL REFERENCES accounts(id),
    amount              NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    captured_amount     NUMERIC(19,4),
    currency            VARCHAR(3)    NOT NULL,
    status              VARCHAR(20)   NOT NULL,           -- ACTIVE | RELEASED | CAPTURED | EXPIRED
    reference           VARCHAR(100)  NOT NULL,           -- caller-supplied, makes creation idempotent
    reason              VARCHAR(255),
    expires_at          TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    capture_journal_id  UUID REFERENCES journal_entries(id),
    CONSTRAINT uq_hold_account_reference UNIQUE (account_id, reference),
    CONSTRAINT ck_hold_captured_amount CHECK (captured_amount IS NULL OR (captured_amount > 0 AND captured_amount <= amount))
);

CREATE INDEX idx_holds_account_active ON holds (account_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_holds_active_expiry ON holds (expires_at) WHERE status = 'ACTIVE';
