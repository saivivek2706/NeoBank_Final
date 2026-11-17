CREATE TABLE mfa_credentials (
    user_id    UUID PRIMARY KEY REFERENCES users(id),
    secret     VARCHAR(64) NOT NULL,
    enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);
