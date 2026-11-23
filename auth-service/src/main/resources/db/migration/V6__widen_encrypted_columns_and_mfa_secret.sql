-- AES-GCM ciphertext includes a random IV, authentication tag and Base64
-- expansion. TEXT avoids truncating valid UTF-8 names or encrypted payloads.
ALTER TABLE users ALTER COLUMN email TYPE TEXT;
ALTER TABLE users ALTER COLUMN full_name TYPE TEXT;

-- 160-bit Base32 TOTP secrets expand when AES-GCM metadata is Base64 encoded.
ALTER TABLE mfa_credentials ALTER COLUMN secret TYPE TEXT;
