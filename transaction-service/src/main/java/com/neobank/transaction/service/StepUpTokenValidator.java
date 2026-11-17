package com.neobank.transaction.service;

import com.neobank.transaction.exception.StepUpRequiredException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Validates the step-up token a high-value transfer must carry. Trust here
 * comes entirely from the signature (verified via auth-service's JWKS, same
 * as any other request's access token) - this service never calls back to
 * auth-service synchronously to check it.
 *
 * Four things must hold, all checked independently rather than trusting any
 * single one:
 *  1. Signature + expiry valid (JwtDecoder throws otherwise)
 *  2. purpose == TRANSFER_STEP_UP (so a login-challenge token, which is
 *     signed with the same key, can't be replayed here for a different purpose)
 *  3. sub == the user actually making this transfer (so user A can't reuse
 *     a step-up token that was issued to user B)
 *  4. jti hasn't been used before (SETNX in Redis, TTL = remaining token
 *     validity) - without this, a single step-up code could authorize
 *     unlimited transfers until it expired.
 */
@Service
@RequiredArgsConstructor
public class StepUpTokenValidator {

    private static final String USED_JTI_PREFIX = "stepup:used:";

    private final JwtDecoder jwtDecoder;
    private final StringRedisTemplate redisTemplate;

    public void requireValid(String stepUpToken, UUID expectedUserId) {
        if (stepUpToken == null || stepUpToken.isBlank()) {
            throw new StepUpRequiredException(
                    "This transfer requires step-up verification. Call /api/v1/auth/mfa/step-up with a fresh code first.");
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(stepUpToken);
        } catch (JwtException e) {
            throw new StepUpRequiredException("Step-up token is invalid or expired - request a new one.");
        }

        String purpose = jwt.getClaimAsString("purpose");
        if (!"TRANSFER_STEP_UP".equals(purpose)) {
            throw new StepUpRequiredException("Token supplied is not a valid transfer step-up token.");
        }
        if (!expectedUserId.toString().equals(jwt.getSubject())) {
            throw new StepUpRequiredException("Step-up token does not belong to the requesting user.");
        }

        String jti = jwt.getId();
        Duration remainingTtl = Duration.between(Instant.now(), jwt.getExpiresAt());
        if (remainingTtl.isNegative() || remainingTtl.isZero()) {
            throw new StepUpRequiredException("Step-up token is invalid or expired - request a new one.");
        }
        Boolean firstUse = redisTemplate.opsForValue()
                .setIfAbsent(USED_JTI_PREFIX + jti, "1", remainingTtl);
        if (firstUse == null || !firstUse) {
            throw new StepUpRequiredException("This step-up token has already been used - request a new one.");
        }
    }
}
