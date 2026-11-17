package com.neobank.auth.service;

import com.neobank.auth.domain.Role;
import com.neobank.auth.domain.User;
import com.neobank.auth.dto.AuthDtos.*;
import com.neobank.auth.exception.InvalidCredentialsException;
import com.neobank.auth.repository.MfaCredentialRepository;
import com.neobank.auth.repository.UserRepository;
import com.neobank.auth.security.BlindIndexService;
import com.neobank.auth.security.RsaKeyProperties;
import com.neobank.auth.security.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private MfaCredentialRepository mfaCredentialRepository;
    @Mock private TotpService totpService;
    @Mock private RsaKeyProperties rsaKeyProperties;
    @Mock private BlindIndexService blindIndexService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, mfaCredentialRepository, passwordEncoder, jwtService,
                refreshTokenService, totpService, rsaKeyProperties, blindIndexService, 5, 15);
    }

    @Test
    void register_throws_whenEmailAlreadyTaken() {
        RegisterRequest request = new RegisterRequest("taken@neobank.dev", "password123", "Someone");
        when(blindIndexService.hashEmail("taken@neobank.dev")).thenReturn("hash-taken@neobank.dev");
        when(userRepository.existsByEmailHash("hash-taken@neobank.dev")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request, "test-device-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_hashesPassword_andIssuesTokens() {
        RegisterRequest request = new RegisterRequest("new@neobank.dev", "password123", "New User");
        when(blindIndexService.hashEmail("new@neobank.dev")).thenReturn("hash-new@neobank.dev");
        when(userRepository.existsByEmailHash("hash-new@neobank.dev")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(jwtService.generateAccessToken(any())).thenReturn("access-token-123");
        when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);
        when(refreshTokenService.issue(any(), any())).thenReturn("refresh-token-456");

        TokenResponse response = authService.register(request, "test-device-1");

        assertThat(response.accessToken()).isEqualTo("access-token-123");
        assertThat(response.refreshToken()).isEqualTo("refresh-token-456");
        assertThat(response.role()).isEqualTo("CUSTOMER");

        verify(userRepository).save(argThat(u ->
                u.getEmail().equals("new@neobank.dev") && u.getPasswordHash().equals("hashed-password")));
    }

    @Test
    void login_throws_whenUserNotFound() {
        LoginRequest request = new LoginRequest("ghost@neobank.dev", "password123");
        when(blindIndexService.hashEmail("ghost@neobank.dev")).thenReturn("hash-ghost@neobank.dev");
        when(userRepository.findByEmailHash("hash-ghost@neobank.dev")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request, "test-device-1"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_throws_whenPasswordDoesNotMatch() throws Exception {
        User user = buildUser("real@neobank.dev", "correct-hash", Role.CUSTOMER, true);
        LoginRequest request = new LoginRequest("real@neobank.dev", "wrong-password");

        when(blindIndexService.hashEmail("real@neobank.dev")).thenReturn("hash-real@neobank.dev");
        when(userRepository.findByEmailHash("hash-real@neobank.dev")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "correct-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request, "test-device-1"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_throws_whenAccountDisabled() throws Exception {
        User user = buildUser("disabled@neobank.dev", "correct-hash", Role.CUSTOMER, false);
        LoginRequest request = new LoginRequest("disabled@neobank.dev", "correct-password");

        when(blindIndexService.hashEmail("disabled@neobank.dev")).thenReturn("hash-disabled@neobank.dev");
        when(userRepository.findByEmailHash("hash-disabled@neobank.dev")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "correct-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request, "test-device-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void login_succeeds_andIssuesTokens_whenCredentialsValid() throws Exception {
        User user = buildUser("good@neobank.dev", "correct-hash", Role.CUSTOMER, true);
        LoginRequest request = new LoginRequest("good@neobank.dev", "correct-password");

        when(blindIndexService.hashEmail("good@neobank.dev")).thenReturn("hash-good@neobank.dev");
        when(userRepository.findByEmailHash("hash-good@neobank.dev")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "correct-hash")).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("access-xyz");
        when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);
        when(refreshTokenService.issue(eq(user.getId()), eq("test-device-1"))).thenReturn("refresh-xyz");

        LoginOutcome outcome = authService.login(request, "test-device-1");

        assertThat(outcome).isInstanceOf(LoginOutcome.Success.class);
        TokenResponse response = ((LoginOutcome.Success) outcome).tokens();
        assertThat(response.accessToken()).isEqualTo("access-xyz");
        assertThat(response.userId()).isEqualTo(user.getId());
    }

    @Test
    void login_locksAccount_afterMaxFailedAttempts() throws Exception {
        User user = buildUser("target@neobank.dev", "correct-hash", Role.CUSTOMER, true);
        LoginRequest request = new LoginRequest("target@neobank.dev", "wrong-password");

        when(blindIndexService.hashEmail("target@neobank.dev")).thenReturn("hash-target@neobank.dev");
        when(userRepository.findByEmailHash("hash-target@neobank.dev")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "correct-hash")).thenReturn(false);

        // First 4 failures (maxFailedAttempts=5 in setUp) should still be "just" invalid credentials.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(request, "test-device-1"))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(user.isLocked()).isFalse();

        // The 5th failure crosses the threshold and locks the account.
        assertThatThrownBy(() -> authService.login(request, "test-device-1"))
                .isInstanceOf(com.neobank.auth.exception.AccountLockedException.class);
        assertThat(user.isLocked()).isTrue();

        // Even with the correct password, a locked account must still be rejected.
        when(passwordEncoder.matches("correct-password", "correct-hash")).thenReturn(true);
        assertThatThrownBy(() -> authService.login(new LoginRequest("target@neobank.dev", "correct-password"), "test-device-1"))
                .isInstanceOf(com.neobank.auth.exception.AccountLockedException.class);
    }

    @Test
    void login_resetsFailedAttempts_onSuccess() throws Exception {
        User user = buildUser("recover@neobank.dev", "correct-hash", Role.CUSTOMER, true);
        when(blindIndexService.hashEmail("recover@neobank.dev")).thenReturn("hash-recover@neobank.dev");
        when(userRepository.findByEmailHash("hash-recover@neobank.dev")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "correct-hash")).thenReturn(false);
        when(passwordEncoder.matches("correct-password", "correct-hash")).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("access-xyz");
        when(jwtService.getAccessTokenTtlSeconds()).thenReturn(900L);
        when(refreshTokenService.issue(eq(user.getId()), eq("test-device-1"))).thenReturn("refresh-xyz");

        assertThatThrownBy(() -> authService.login(new LoginRequest("recover@neobank.dev", "wrong-password"), "test-device-1"))
                .isInstanceOf(InvalidCredentialsException.class);

        authService.login(new LoginRequest("recover@neobank.dev", "correct-password"), "test-device-1");

        assertThat(user.isLocked()).isFalse();
    }

    @Test
    void login_returnsMfaChallenge_insteadOfTokens_whenMfaEnabled() throws Exception {
        User user = buildUser("mfa-user@neobank.dev", "correct-hash", Role.CUSTOMER, true);
        LoginRequest request = new LoginRequest("mfa-user@neobank.dev", "correct-password");

        com.neobank.auth.domain.MfaCredential enabledMfa =
                com.neobank.auth.domain.MfaCredential.pendingEnrollment(user.getId(), "SECRETSECRETSECRETSE");
        setField(enabledMfa, "enabled", true);

        when(blindIndexService.hashEmail("mfa-user@neobank.dev")).thenReturn("hash-mfa-user@neobank.dev");
        when(userRepository.findByEmailHash("hash-mfa-user@neobank.dev")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "correct-hash")).thenReturn(true);
        when(mfaCredentialRepository.findById(user.getId())).thenReturn(Optional.of(enabledMfa));
        when(jwtService.generatePurposeToken(eq(user.getId()), eq("MFA_LOGIN_CHALLENGE"), any()))
                .thenReturn("challenge-token-abc");

        LoginOutcome outcome = authService.login(request, "test-device-1");

        assertThat(outcome).isInstanceOf(LoginOutcome.MfaRequired.class);
        assertThat(((LoginOutcome.MfaRequired) outcome).challengeToken()).isEqualTo("challenge-token-abc");
        // A correct password still must not issue real tokens on its own when MFA is enabled.
        verify(jwtService, never()).generateAccessToken(any());
    }

    /** Reflection helper: User's constructor is private (factory method only exposes `register`, which always sets CUSTOMER + enabled=true), so tests that need specific role/enabled states build directly. */
    private User buildUser(String email, String passwordHash, Role role, boolean enabled) throws Exception {
        User user = User.register(email, "hash-" + email, passwordHash, "Test User");
        setField(user, "id", UUID.randomUUID());
        setField(user, "role", role);
        setField(user, "enabled", enabled);
        return user;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
