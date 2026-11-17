package com.neobank.auth.service;

import com.neobank.auth.domain.User;
import com.neobank.auth.security.RsaKeyProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues short-lived RS256 access tokens signed with auth-service's private
 * key. Every downstream service independently verifies the signature using
 * only the corresponding public key (fetched from /.well-known/jwks.json) -
 * no network call back to auth-service is needed per request, and no
 * downstream service ever holds anything capable of forging a token.
 */
@Service
public class JwtService {

    private final RsaKeyProperties keys;
    private final long accessTokenTtlSeconds;

    public JwtService(RsaKeyProperties keys,
                       @Value("${security.jwt.access-token-ttl-seconds:900}") long accessTokenTtlSeconds) {
        this.keys = keys;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().add("kid", keys.getKeyId()).and()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("fullName", user.getFullName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessTokenTtlSeconds)))
                .signWith(keys.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();
    }

    /**
     * Short-lived, single-purpose tokens used for MFA flows:
     *  - purpose=MFA_LOGIN_CHALLENGE: proves the password check already
     *    passed, so the /mfa/login-challenge endpoint only needs a TOTP code
     *    to finish signing the user in - without this, that endpoint would
     *    have to accept a raw userId from the client, which anyone could
     *    supply for any account.
     *  - purpose=TRANSFER_STEP_UP: proves the caller freshly proved their
     *    TOTP code, scoped to a specific user (`sub`) and single-use via
     *    `jti` (enforced by whoever consumes the token - see
     *    transaction-service's StepUpTokenValidator).
     * Verified the same way as access tokens (JWKS), by any service that
     * needs to check one - no shared secret, no new key material.
     */
    public String generatePurposeToken(UUID subject, String purpose, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().add("kid", keys.getKeyId()).and()
                .subject(subject.toString())
                .id(UUID.randomUUID().toString())
                .claim("purpose", purpose)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(keys.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }
}
