package com.neobank.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * User.email is encrypted at rest with a random IV per row (see
 * EncryptedStringConverter), which means two rows with the identical email
 * produce completely different ciphertext - so the database can no longer
 * enforce "email is unique" or answer "find the user with this email" via a
 * normal column comparison.
 *
 * This is the standard fix: a deterministic HMAC of the (normalized) email,
 * stored in a separate, unencrypted, indexed column (User.emailHash). Unlike
 * the AES key, this key must produce the SAME output for the SAME input
 * every time, so it cannot use a random IV - that's an intentional,
 * necessary trade-off, not an oversight. It doesn't reveal the email itself
 * (HMAC is one-way), but two rows sharing an email WILL have matching hashes
 * - which is exactly the property being relied on for the uniqueness
 * constraint and login lookup, and is a normal, accepted characteristic of
 * blind indexing.
 */
@Component
public class BlindIndexService {

    private final SecretKeySpec key;

    public BlindIndexService(@Value("${security.pii.blind-index-key-b64}") String base64Key) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "HmacSHA256");
    }

    public String hashEmail(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] normalized = email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(mac.doFinal(normalized));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute email blind index", e);
        }
    }
}
