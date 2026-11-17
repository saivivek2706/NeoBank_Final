package com.neobank.auth.controller;

import com.neobank.auth.dto.AuthDtos.*;
import com.neobank.auth.service.AuthService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * /enroll and /enroll/verify require an existing authenticated session
 * (SecurityConfig only permits /mfa/login-challenge without one) - a user
 * must already be logged in with their password to turn MFA on, same as
 * every real bank's "enable 2FA" flow.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@RequiredArgsConstructor
public class MfaController {

    private final AuthService authService;

    @PostMapping("/enroll")
    public MfaEnrollResponse enroll(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String email = jwt.getClaimAsString("email");
        return authService.startMfaEnrollment(userId, email);
    }

    @PostMapping("/enroll/verify")
    public ResponseEntity<Void> verifyEnrollment(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody MfaCodeRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        authService.confirmMfaEnrollment(userId, request.code());
        return ResponseEntity.noContent().build();
    }

    /** Second step of login when MFA is enabled - no existing session yet, only the challenge token from step one. */
    @PostMapping("/login-challenge")
    @RateLimiter(name = "authEndpoints")
    public ResponseEntity<SessionResponse> completeLogin(@Valid @RequestBody MfaLoginChallengeRequest request,
                                                          @CookieValue(name = "device_id", required = false) String existingDeviceId) {
        // Step one (/auth/login) already set the device_id cookie even on the
        // MfaRequired branch, so this should normally just be echoing it back -
        // the fallback only matters if a client somehow calls this endpoint
        // without ever having received that cookie.
        String deviceId = existingDeviceId != null && !existingDeviceId.isBlank()
                ? existingDeviceId : UUID.randomUUID().toString();
        TokenResponse tokens = authService.completeMfaLogin(request.challengeToken(), request.code(), deviceId);
        ResponseCookie accessCookie = ResponseCookie.from("access_token", tokens.accessToken())
                .httpOnly(true).secure(true).sameSite("Strict").path("/").maxAge(tokens.expiresInSeconds()).build();
        ResponseCookie refreshCookie = ResponseCookie.from("refresh_token", tokens.refreshToken())
                .httpOnly(true).secure(true).sameSite("Strict").path("/api/v1/auth")
                .maxAge(java.time.Duration.ofDays(7)).build();
        ResponseCookie deviceCookie = ResponseCookie.from("device_id", deviceId)
                .httpOnly(true).secure(true).sameSite("Strict").path("/")
                .maxAge(java.time.Duration.ofDays(365)).build();
        return ResponseEntity.status(HttpStatus.OK)
                .header("Set-Cookie", accessCookie.toString())
                .header("Set-Cookie", refreshCookie.toString())
                .header("Set-Cookie", deviceCookie.toString())
                .body(SessionResponse.from(tokens));
    }

    /**
     * Called by the frontend right before submitting a high-value transfer.
     * Requires the existing session (the caller is already logged in) plus a
     * fresh TOTP code, and hands back a short-lived, single-use token that
     * transaction-service will require on the transfer request itself.
     */
    @PostMapping("/step-up")
    public StepUpTokenResponse stepUp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MfaCodeRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String token = authService.issueStepUpToken(userId, request.code());
        return new StepUpTokenResponse(token);
    }
}
