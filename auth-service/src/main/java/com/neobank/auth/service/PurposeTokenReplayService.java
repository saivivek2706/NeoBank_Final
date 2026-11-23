package com.neobank.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** Atomic, shared single-use tracking for short-lived signed purpose tokens. */
@Service
@RequiredArgsConstructor
public class PurposeTokenReplayService {

    private final StringRedisTemplate redisTemplate;

    public boolean consume(String tokenId, Instant expiresAt) {
        if (tokenId == null || tokenId.isBlank() || expiresAt == null) return false;
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) return false;
        Boolean inserted = redisTemplate.opsForValue().setIfAbsent(
                "consumed_purpose_token:" + tokenId, "1", ttl);
        // Fail closed if Redis cannot confirm the atomic write.
        return Boolean.TRUE.equals(inserted);
    }
}
