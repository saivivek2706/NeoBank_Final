package com.neobank.auth.controller;

import com.neobank.auth.dto.AuthDtos.*;
import com.neobank.auth.service.AuthService;
import com.neobank.auth.service.LoginOutcome;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Tokens are never returned in the JSON response body. Instead they're set as
 * httpOnly, Secure, SameSite=Strict cookies, so client-side JavaScript (and
 * therefore any XSS payload that might run in the SPA) can never read or
 * exfiltrate them. The browser sends them back automatically on subsequent
 * requests to this same origin (via the gateway) - the frontend never
 * touches the token value at all.
 *
 * SameSite=Strict is what removes the CSRF risk that cookie-based auth
 * usually reintroduces: the cookie is only attached to requests that
 * originate from this site itself, so a malicious third-party page cannot
 * trigger an authenticated request against our API using the victim's
 * cookie. This is enough for a same-origin SPA; it would NOT be enough if
 * we ever needed to accept authenticated requests embedded from another
 * origin - that would need double-submit CSRF tokens on top.
 *
 * A `device_id` cookie (random, long-lived, httpOnly) is also (re)issued on
 * every successful auth here - it's what RefreshTokenService binds refresh
 * tokens to, so a stolen refresh token alone can't be replayed from a
 * different browser/device (see RefreshTokenService for the full reasoning).
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String ACCESS_COOKIE = "access_token";
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String DEVICE_COOKIE = "device_id";

    private final AuthService authService;

    @PostMapping("/register")
    @RateLimiter(name = "authEndpoints")
    public ResponseEntity<SessionResponse> register(@Valid @RequestBody RegisterRequest request,
                                                      @CookieValue(name = DEVICE_COOKIE, required = false) String existingDeviceId) {
        String deviceId = resolveDeviceId(existingDeviceId);
        TokenResponse tokens = authService.register(request, deviceId);
        return withAuthCookies(HttpStatus.CREATED, tokens, deviceId);
    }

    @PostMapping("/login")
    @RateLimiter(name = "authEndpoints")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                    @CookieValue(name = DEVICE_COOKIE, required = false) String existingDeviceId) {
        String deviceId = resolveDeviceId(existingDeviceId);
        LoginOutcome outcome = authService.login(request, deviceId);
        return switch (outcome) {
            case LoginOutcome.MfaRequired mfaRequired ->
                    // Device isn't bound to anything yet at this point (no tokens issued) -
                    // it gets bound once /mfa/login-challenge actually completes login.
                    ResponseEntity.ok()
                            .header("Set-Cookie", deviceCookie(deviceId).toString())
                            .body(new MfaChallengeResponse(true, mfaRequired.challengeToken()));
            case LoginOutcome.Success success -> withAuthCookies(HttpStatus.OK, success.tokens(), deviceId);
        };
    }

    @PostMapping("/refresh")
    @RateLimiter(name = "authEndpoints")
    public ResponseEntity<SessionResponse> refresh(@CookieValue(REFRESH_COOKIE) String refreshToken,
                                                     @CookieValue(name = DEVICE_COOKIE, required = false) String existingDeviceId) {
        String deviceId = resolveDeviceId(existingDeviceId);
        TokenResponse tokens = authService.refresh(new RefreshRequest(refreshToken), deviceId);
        return withAuthCookies(HttpStatus.OK, tokens, deviceId);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null) {
            authService.logout(refreshToken);
        }
        // device_id is deliberately NOT cleared on logout - it identifies the
        // physical device, not the session, so the next login from this same
        // browser is still recognized as a known device rather than flagged new.
        ResponseCookie clearAccess = ResponseCookie.from(ACCESS_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(0).build();
        ResponseCookie clearRefresh = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth").maxAge(0).build();
        return ResponseEntity.noContent()
                .header("Set-Cookie", clearAccess.toString())
                .header("Set-Cookie", clearRefresh.toString())
                .build();
    }

    private String resolveDeviceId(String existing) {
        return existing != null && !existing.isBlank() ? existing : UUID.randomUUID().toString();
    }

    private ResponseCookie deviceCookie(String deviceId) {
        return ResponseCookie.from(DEVICE_COOKIE, deviceId)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(java.time.Duration.ofDays(365))
                .build();
    }

    private ResponseEntity<SessionResponse> withAuthCookies(HttpStatus status, TokenResponse tokens, String deviceId) {
        ResponseCookie accessCookie = ResponseCookie.from(ACCESS_COOKIE, tokens.accessToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(tokens.expiresInSeconds())
                .build();
        // Refresh token TTL (7 days) is fixed in RefreshTokenService; cookie maxAge mirrors it here.
        ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_COOKIE, tokens.refreshToken())
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(java.time.Duration.ofDays(7))
                .build();
        return ResponseEntity.status(status)
                .header("Set-Cookie", accessCookie.toString())
                .header("Set-Cookie", refreshCookie.toString())
                .header("Set-Cookie", deviceCookie(deviceId).toString())
                .body(SessionResponse.from(tokens));
    }
}
