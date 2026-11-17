package com.neobank.auth.security;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Minimal RFC 6238 (TOTP) / RFC 4226 (HOTP) implementation using only JDK
 * crypto (HmacSHA1 via javax.crypto.Mac) - deliberately no external MFA
 * library, so there's one fewer third-party dependency touching the
 * authentication path. This is the same algorithm Google Authenticator,
 * Authy, and every bank's TOTP-based 2FA use.
 */
@Service
public class TotpService {

    private static final int SECRET_BYTES = 20; // 160 bits, standard TOTP secret length
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;
    private static final int ALLOWED_DRIFT_STEPS = 1; // accepts the previous/next 30s window too

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** Generates a new random secret for enrollment, base32-encoded (the form authenticator apps expect). */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** Standard otpauth:// URI that authenticator apps turn into a scannable QR code. */
    public String provisioningUri(String base32Secret, String accountEmail, String issuer) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountEmail);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /** Verifies a 6-digit code against the secret, allowing +/- one 30s step for clock drift. */
    public boolean verifyCode(String base32Secret, String code) {
        if (code == null || !code.matches("\\d{6}")) return false;
        long currentStep = System.currentTimeMillis() / 1000 / PERIOD_SECONDS;
        byte[] key = base32Decode(base32Secret);
        for (long stepOffset = -ALLOWED_DRIFT_STEPS; stepOffset <= ALLOWED_DRIFT_STEPS; stepOffset++) {
            if (generateCode(key, currentStep + stepOffset).equals(code)) {
                return true;
            }
        }
        return false;
    }

    private String generateCode(byte[] key, long counter) {
        try {
            byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counterBytes);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format(Locale.ROOT, "%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute TOTP code", e);
        }
    }

    private String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0, value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private byte[] base32Decode(String encoded) {
        String clean = encoded.trim().toUpperCase(Locale.ROOT).replace("=", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int bits = 0, value = 0;
        for (char c : clean.toCharArray()) {
            int idx = BASE32_ALPHABET.indexOf(c);
            if (idx < 0) continue;
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
