package com.neobank.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private SetOperations<String, String> setOperations;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(redisTemplate);
    }

    @Test
    void issue_storesUserIdAndDeviceIdTogether() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UUID userId = UUID.randomUUID();

        String token = service.issue(userId, "device-A");

        verify(valueOperations).set(eq("refresh_token:" + token), eq(userId + "|device-A"), any(Duration.class));
    }

    @Test
    void validateAndRotate_succeeds_whenDeviceMatches() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UUID userId = UUID.randomUUID();
        when(valueOperations.get("refresh_token:tok-1")).thenReturn(userId + "|device-A");

        Optional<UUID> result = service.validateAndRotate("tok-1", "device-A");

        assertThat(result).contains(userId);
        // One-time use: the token must be deleted regardless of outcome.
        verify(redisTemplate).delete("refresh_token:tok-1");
    }

    @Test
    void validateAndRotate_rejects_whenDeviceDiffers() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UUID userId = UUID.randomUUID();
        when(valueOperations.get("refresh_token:tok-1")).thenReturn(userId + "|device-A");

        // Same valid token, but presented from a different device than it was issued to.
        Optional<UUID> result = service.validateAndRotate("tok-1", "device-B");

        assertThat(result).isEmpty();
        // Still burned - a mismatched presentation is treated as a possible
        // theft attempt, not a request to just try again.
        verify(redisTemplate).delete("refresh_token:tok-1");
    }

    @Test
    void validateAndRotate_returnsEmpty_whenTokenUnknown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThat(service.validateAndRotate("nonexistent", "device-A")).isEmpty();
    }

    @Test
    void registerDeviceAndCheckIfNew_returnsTrue_forFirstSighting() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        UUID userId = UUID.randomUUID();
        when(setOperations.add("known_devices:" + userId, "device-A")).thenReturn(1L);

        assertThat(service.registerDeviceAndCheckIfNew(userId, "device-A")).isTrue();
    }

    @Test
    void registerDeviceAndCheckIfNew_returnsFalse_forAlreadyKnownDevice() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        UUID userId = UUID.randomUUID();
        // SADD returns 0 when the member was already present in the set.
        when(setOperations.add("known_devices:" + userId, "device-A")).thenReturn(0L);

        assertThat(service.registerDeviceAndCheckIfNew(userId, "device-A")).isFalse();
    }
}
