package com.neobank.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh tokens are opaque random strings (not JWTs) stored in Redis, mapped
 * to the owning userId AND the device that requested them, with a TTL.
 * Storing them server-side (rather than as long-lived JWTs) means a
 * compromised refresh token can be revoked instantly by deleting the Redis
 * key - something you cannot do with a stateless JWT before its natural
 * expiry. Rotated on every use (old one deleted, new one issued) to limit
 * the blast radius of a leaked token.
 *
 * Device binding: each refresh token is tied to the device_id cookie present
 * when it was issued (see AuthController). If a refresh request presents a
 * valid token but a DIFFERENT device_id than the one it was issued to, that's
 * treated as a stolen-token replay, not a legitimate refresh - the token is
 * rejected and burned, not silently honored. This doesn't stop theft of both
 * the token and the device_id cookie together, but it does stop the far more
 * common case of a refresh token alone leaking (e.g. via a server log, a
 * proxy, or a misconfigured CORS response) and being replayed from an
 * attacker's own browser/device.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final String TOKEN_KEY_PREFIX = "refresh_token:";
    private static final String KNOWN_DEVICES_PREFIX = "known_devices:";
    private static final Duration TTL = Duration.ofDays(7);
    private static final DefaultRedisScript<String> GET_AND_DELETE = new DefaultRedisScript<>(
            "local value = redis.call('GET', KEYS[1]); if value then redis.call('DEL', KEYS[1]); end; return value",
            String.class);

    private final StringRedisTemplate redisTemplate;

    public String issue(UUID userId, String deviceId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token, userId + "|" + deviceId, TTL);
        return token;
    }

    /** Returns the owning userId if the token is valid AND was issued to this same device, otherwise empty. */
    public Optional<UUID> validateAndRotate(String token, String deviceId) {
        String key = TOKEN_KEY_PREFIX + token;
        String stored = redisTemplate.execute(GET_AND_DELETE, List.of(key));
        if (stored == null) {
            return Optional.empty();
        }

        String[] parts = stored.split("\\|", 2);
        String boundUserId = parts[0];
        String boundDeviceId = parts.length > 1 ? parts[1] : null;

        if (boundDeviceId == null || !boundDeviceId.equals(deviceId)) {
            log.warn("Refresh token device mismatch for user {} - rejecting as a possible stolen-token replay", boundUserId);
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(boundUserId));
    }

    public void revoke(String token) {
        redisTemplate.delete(TOKEN_KEY_PREFIX + token);
    }

    /**
     * Records this device as known for this user and reports whether it's the
     * FIRST time we've seen it. A real deployment would use that signal to
     * fire a "new device login" email/push (the same way every major bank
     * does) - this repo logs it instead, since wiring a user-facing
     * notification needs a user-keyed event + contact lookup that
     * notification-service doesn't currently have (it's keyed by account,
     * not by user/email) and forcing that in here would be a much bigger,
     * separate change.
     */
    public boolean registerDeviceAndCheckIfNew(UUID userId, String deviceId) {
        Long added = redisTemplate.opsForSet().add(KNOWN_DEVICES_PREFIX + userId, deviceId);
        return added != null && added > 0;
    }
}
