package com.neobank.auth.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts PII columns (email, full name) at rest using AES-256-GCM, applied
 * transparently by Hibernate via @Convert - the rest of the codebase reads
 * and writes plain Java Strings and never touches ciphertext directly.
 *
 * Each encryption uses a fresh random IV (stored alongside the ciphertext,
 * not secret) so the same plaintext never produces the same stored value
 * twice - which also means this column can no longer be queried by equality
 * in SQL. That's precisely why User.emailHash exists as a separate blind
 * index for login lookups (see BlindIndexService) - the ciphertext itself is
 * intentionally not searchable.
 *
 * The key is loaded directly from PII_ENCRYPTION_KEY_B64 rather than through
 * Spring dependency injection: JPA AttributeConverters are instantiated by
 * Hibernate itself, and wiring Spring beans into them reliably needs
 * Hibernate-version-specific SpringBeanContainer configuration. Reading the
 * key directly avoids that fragility at the cost of not being able to unit
 * test this with a swappable mock key - acceptable for a converter this small.
 *
 * Rotating this key requires re-encrypting every existing row (decrypt with
 * the old key, re-encrypt with the new one) in a one-off migration job - it
 * is NOT a drop-in config change like rotating the JWT signing key is.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private static final SecretKeySpec KEY = loadKey();

    private static SecretKeySpec loadKey() {
        // No built-in default key: a well-known key committed to source control would make "encrypted at
        // rest" meaningless. Generate one with scripts/bootstrap-env.sh (or `openssl rand -base64 32`).
        // The system property exists so unit tests can supply a throwaway key without touching the environment.
        String base64Key = System.getenv("PII_ENCRYPTION_KEY_B64");
        if (base64Key == null || base64Key.isBlank()) {
            base64Key = System.getProperty("pii.encryption.key.b64");
        }
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "PII_ENCRYPTION_KEY_B64 is not set. Generate one with scripts/bootstrap-env.sh or `openssl rand -base64 32`.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("PII_ENCRYPTION_KEY_B64 must decode to exactly 32 bytes (AES-256)");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer combined = ByteBuffer.allocate(iv.length + ciphertext.length);
            combined.put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(combined.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt column value", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String storedValue) {
        if (storedValue == null) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(storedValue);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to decrypt column value - wrong key, or the row was written before " +
                    "encryption was enabled (see AdminUserSeeder for how seed data must be re-created).", e);
        }
    }
}
