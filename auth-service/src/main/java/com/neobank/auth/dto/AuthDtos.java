package com.neobank.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class AuthDtos {

    public record RegisterRequest(
            @Email @NotBlank String email,
            @Size(min = 8, message = "Password must be at least 8 characters") String password,
            @NotBlank String fullName
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    /**
     * Internal service-layer result. Tokens never leave AuthService/AuthController
     * as JSON - they're written straight into httpOnly cookies so client-side
     * JavaScript (and therefore any XSS payload) can never read them.
     */
    public record TokenResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            UUID userId,
            String role
    ) {}

    /** What actually goes back to the browser in the response body - no tokens. */
    public record SessionResponse(
            UUID userId,
            String role,
            long expiresInSeconds
    ) {
        public static SessionResponse from(TokenResponse tokens) {
            return new SessionResponse(tokens.userId(), tokens.role(), tokens.expiresInSeconds());
        }
    }

    /** What the frontend gets back from POST /auth/login when MFA is required - no session yet. */
    public record MfaChallengeResponse(boolean mfaRequired, String challengeToken) {}

    public record MfaLoginChallengeRequest(@NotBlank String challengeToken, @NotBlank String code) {}

    /** secret is shown once during enrollment (also encoded into otpauthUrl for QR scanning) - never returned again afterwards. */
    public record MfaEnrollResponse(String secret, String otpauthUrl) {}

    public record MfaCodeRequest(@NotBlank String code) {}

    public record StepUpTokenResponse(String stepUpToken) {}
}
