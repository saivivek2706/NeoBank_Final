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

/** AES-256-GCM converter for TOTP secrets, using a key separate from PII encryption. */
@Converter
public class EncryptedMfaSecretConverter implements AttributeConverter<String, String> {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecretKeySpec KEY = loadKey();

    private static SecretKeySpec loadKey() {
        String encoded = System.getenv("MFA_ENCRYPTION_KEY_B64");
        if (encoded == null || encoded.isBlank()) {
            encoded = System.getProperty("mfa.encryption.key.b64");
        }
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY_B64 is required");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY_B64 must be valid Base64", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("MFA_ENCRYPTION_KEY_B64 must decode to exactly 32 bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
            payload.put(iv).put(encrypted);
            return PREFIX + Base64.getEncoder().encodeToString(payload.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt MFA secret", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) return null;
        // Backward compatibility: legacy rows are plaintext until the startup
        // migration rewrites them. Never write legacy values back unencrypted.
        if (!stored.startsWith(PREFIX)) return stored;
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH) throw new IllegalArgumentException("Invalid encrypted MFA secret");
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt MFA secret; check MFA_ENCRYPTION_KEY_B64", e);
        }
    }
}
