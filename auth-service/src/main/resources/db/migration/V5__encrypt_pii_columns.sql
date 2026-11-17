-- Email/full_name now hold AES-GCM ciphertext (IV + tag + base64 overhead),
-- which is meaningfully larger than the plaintext - widened accordingly.
ALTER TABLE users ALTER COLUMN email SET DATA TYPE VARCHAR(500);
ALTER TABLE users ALTER COLUMN full_name SET DATA TYPE VARCHAR(500);

-- The old unique constraint/index on `email` is meaningless now: encryption
-- uses a random IV per row, so two rows with the SAME plaintext email have
-- DIFFERENT ciphertext and would never collide anyway. Uniqueness (and
-- lookup-by-email) now lives on the deterministic blind index instead.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
DROP INDEX IF EXISTS idx_users_email;

ALTER TABLE users ADD COLUMN email_hash VARCHAR(64);
-- Backfill note: this migration assumes an empty/fresh users table (true for
-- a new docker-compose environment). Any pre-existing plaintext rows would
-- need an application-level job to compute their hash before this NOT NULL +
-- UNIQUE constraint could be safely added - Flyway SQL alone can't do that,
-- since email_hash must be computed with the app's blind-index key.
ALTER TABLE users ALTER COLUMN email_hash SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_email_hash_key UNIQUE (email_hash);
CREATE INDEX idx_users_email_hash ON users (email_hash);
