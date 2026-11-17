package com.neobank.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Holds the RSA keypair auth-service uses to sign access tokens (RS256).
 *
 * Only auth-service ever sees the private key. Every downstream service
 * verifies signatures using the public key alone, fetched from this
 * service's JWKS endpoint (/.well-known/jwks.json) - so compromising a
 * downstream service can never let an attacker forge tokens for the whole
 * platform, unlike the shared-HMAC-secret approach.
 *
 * Keys are supplied as base64-encoded PEM via env vars (JWT_PRIVATE_KEY_B64 /
 * JWT_PUBLIC_KEY_B64) rather than generated at startup, because a random
 * per-instance keypair would break verification the moment auth-service
 * restarts or runs more than one replica - downstream JWKS caches would go
 * stale and reject valid tokens, and different replicas would disagree on
 * what a valid signature even looks like.
 *
 * Generate a real (non-dev) keypair with:
 *   openssl genrsa -out private.pem 2048
 *   openssl pkcs8 -topk8 -nocrypt -in private.pem -out private_pkcs8.pem
 *   openssl rsa -in private.pem -pubout -out public.pem
 *   base64 -w0 private_pkcs8.pem   # -> JWT_PRIVATE_KEY_B64
 *   base64 -w0 public.pem          # -> JWT_PUBLIC_KEY_B64
 * and store them in your secrets manager (Vault/AWS Secrets Manager/etc),
 * never in source control. Rotate by publishing the new public key under a
 * new "kid" alongside the old one in the JWKS response until every
 * previously-issued access token has expired, then retire the old key.
 */
@Component
public class RsaKeyProperties {

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String keyId;

    public RsaKeyProperties(@Value("${security.jwt.private-key-b64}") String privateKeyB64,
                             @Value("${security.jwt.public-key-b64}") String publicKeyB64,
                             @Value("${security.jwt.key-id:neobank-dev-key-1}") String keyId) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");

            byte[] privateBytes = decodePem(privateKeyB64, "PRIVATE KEY");
            this.privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));

            byte[] publicBytes = decodePem(publicKeyB64, "PUBLIC KEY");
            this.publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(publicBytes));

            this.keyId = keyId;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                    "Failed to load RSA signing keypair from security.jwt.private-key-b64 / public-key-b64. " +
                    "Make sure both are base64-encoded PEM files (see RsaKeyProperties javadoc for generation steps).", e);
        }
    }

    private static byte[] decodePem(String base64Pem, String expectedMarker) {
        String pem = new String(Base64.getDecoder().decode(base64Pem));
        String cleaned = pem
                .replace("-----BEGIN " + expectedMarker + "-----", "")
                .replace("-----END " + expectedMarker + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }

    public RSAPrivateKey getPrivateKey() {
        return privateKey;
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    public String getKeyId() {
        return keyId;
    }
}
