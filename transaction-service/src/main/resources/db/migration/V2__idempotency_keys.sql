-- Idempotent transfer initiation (Stripe-style Idempotency-Key header).
--
-- A client that retries POST /transfer after a timeout, a dropped connection or
-- a double-click must NOT move the money twice. The caller supplies a unique
-- key per logical transfer; (requested_by, idempotency_key) is unique, so the
-- database itself - not just application code - rejects a second insert, even
-- when two retries race each other on different replicas.
--
-- request_fingerprint is a SHA-256 of the business fields of the original
-- request, used to detect a key being reused for a DIFFERENT transfer (HTTP 422)
-- rather than silently returning the wrong transaction.
ALTER TABLE transactions
    ADD COLUMN requested_by         UUID,
    ADD COLUMN idempotency_key      VARCHAR(100),
    ADD COLUMN request_fingerprint  VARCHAR(64);

-- Partial unique index: rows created before this migration have no key.
CREATE UNIQUE INDEX uq_tx_requester_idempotency_key
    ON transactions (requested_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
