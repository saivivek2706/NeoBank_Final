package com.neobank.auth.service;

import com.neobank.auth.domain.MfaCredential;
import com.neobank.auth.domain.User;
import com.neobank.auth.dto.AuthDtos.*;
import com.neobank.auth.exception.AccountLockedException;
import com.neobank.auth.exception.InvalidCredentialsException;
import com.neobank.auth.repository.MfaCredentialRepository;
import com.neobank.auth.repository.UserRepository;
import com.neobank.auth.security.BlindIndexService;
import com.neobank.auth.security.RsaKeyProperties;
import com.neobank.auth.security.TotpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class AuthService {

    private static final String PURPOSE_MFA_LOGIN_CHALLENGE = "MFA_LOGIN_CHALLENGE";

    private final UserRepository userRepository;
    private final MfaCredentialRepository mfaCredentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final TotpService totpService;
    private final RsaKeyProperties rsaKeys;
    private final BlindIndexService blindIndexService;
    private final int maxFailedAttempts;
    private final long lockoutDurationMinutes;

    public AuthService(UserRepository userRepository,
                        MfaCredentialRepository mfaCredentialRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        RefreshTokenService refreshTokenService,
                        TotpService totpService,
                        RsaKeyProperties rsaKeys,
                        BlindIndexService blindIndexService,
                        @Value("${security.lockout.max-attempts:5}") int maxFailedAttempts,
                        @Value("${security.lockout.duration-minutes:15}") long lockoutDurationMinutes) {
        this.userRepository = userRepository;
        this.mfaCredentialRepository = mfaCredentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.totpService = totpService;
        this.rsaKeys = rsaKeys;
        this.blindIndexService = blindIndexService;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockoutDurationMinutes = lockoutDurationMinutes;
    }

    @Transactional
    public TokenResponse register(RegisterRequest request, String deviceId) {
        String emailHash = blindIndexService.hashEmail(request.email());
        if (userRepository.existsByEmailHash(emailHash)) {
            throw new IllegalArgumentException("An account with this email already exists");
        }
        User user = User.register(request.email(), emailHash, passwordEncoder.encode(request.password()), request.fullName());
        userRepository.save(user);
        return issueTokens(user, deviceId);
    }

    /**
     * Account lockout: after `maxFailedAttempts` consecutive bad passwords for
     * the SAME account, that account is locked for `lockoutDurationMinutes`
     * regardless of which IP the attempts came from. This stops credential
     * stuffing / password guessing against a specific victim account.
     *
     * This is deliberately separate from (and in addition to) the per-IP
     * request rate limiting on this endpoint at the gateway, which stops an
     * attacker from spraying many different accounts quickly.
     *
     * Whether the account doesn't exist or the password is wrong, the caller
     * gets the identical "Invalid email or password" 401 - never reveal
     * account existence via response differences. A locked account is
     * deliberately distinguishable (423) so a legitimate user knows to stop
     * guessing and wait, rather than assuming they mistyped the password.
     */
    @Transactional
    public LoginOutcome login(LoginRequest request, String deviceId) {
        User user = userRepository.findByEmailHash(blindIndexService.hashEmail(request.email()))
                .orElseThrow(InvalidCredentialsException::new);

        if (user.isLocked()) {
            throw new AccountLockedException();
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.registerFailedLogin(maxFailedAttempts, Duration.ofMinutes(lockoutDurationMinutes));
            userRepository.save(user);
            if (user.isLocked()) {
                throw new AccountLockedException();
            }
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled()) {
            throw new IllegalStateException("Account is disabled");
        }

        user.registerSuccessfulLogin();
        userRepository.save(user);

        Optional<MfaCredential> mfa = mfaCredentialRepository.findById(user.getId());
        if (mfa.isPresent() && mfa.get().isEnabled()) {
            // Password was correct, but that alone isn't enough to finish signing
            // in - hand back a short-lived challenge token scoped to exactly this
            // user, which /mfa/login-challenge will require alongside a TOTP code.
            String challengeToken = jwtService.generatePurposeToken(user.getId(), PURPOSE_MFA_LOGIN_CHALLENGE,
                    Duration.ofMinutes(5));
            return new LoginOutcome.MfaRequired(challengeToken);
        }

        return new LoginOutcome.Success(issueTokens(user, deviceId));
    }

    /** Second step of login when MFA is enabled: challenge token (proves password was already correct) + TOTP code. */
    @Transactional
    public TokenResponse completeMfaLogin(String challengeToken, String code, String deviceId) {
        UUID userId = verifyPurposeToken(challengeToken, PURPOSE_MFA_LOGIN_CHALLENGE);
        User user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        MfaCredential mfa = mfaCredentialRepository.findById(userId)
                .filter(MfaCredential::isEnabled)
                .orElseThrow(InvalidCredentialsException::new);

        if (!totpService.verifyCode(mfa.getSecret(), code)) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(user, deviceId);
    }

    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshRequest request, String deviceId) {
        UUID userId = refreshTokenService.validateAndRotate(request.refreshToken(), deviceId)
                .orElseThrow(InvalidCredentialsException::new);
        User user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        return issueTokens(user, deviceId);
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    // ---- MFA enrollment (called while already authenticated with a normal session) ----

    @Transactional
    public MfaEnrollResponse startMfaEnrollment(UUID userId, String email) {
        String secret = totpService.generateSecret();
        mfaCredentialRepository.save(MfaCredential.pendingEnrollment(userId, secret));
        return new MfaEnrollResponse(secret, totpService.provisioningUri(secret, email, "NeoBank"));
    }

    @Transactional
    public void confirmMfaEnrollment(UUID userId, String code) {
        MfaCredential mfa = mfaCredentialRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("No MFA enrollment in progress"));
        if (!totpService.verifyCode(mfa.getSecret(), code)) {
            throw new InvalidCredentialsException();
        }
        mfa.activate();
        mfaCredentialRepository.save(mfa);
    }

    /** Used by other services (e.g. transaction-service, for high-value transfers) to step up an already-authenticated session. */
    @Transactional(readOnly = true)
    public String issueStepUpToken(UUID userId, String code) {
        MfaCredential mfa = mfaCredentialRepository.findById(userId)
                .filter(MfaCredential::isEnabled)
                .orElseThrow(() -> new IllegalStateException("MFA is not enabled for this account"));
        if (!totpService.verifyCode(mfa.getSecret(), code)) {
            throw new InvalidCredentialsException();
        }
        return jwtService.generatePurposeToken(userId, "TRANSFER_STEP_UP", Duration.ofMinutes(2));
    }

    private UUID verifyPurposeToken(String token, String expectedPurpose) {
        // Challenge tokens are verified the same way any other service would -
        // by signature - rather than trusted implicitly just because they came
        // back through this same process.
        try {
            io.jsonwebtoken.Jws<io.jsonwebtoken.Claims> jws = io.jsonwebtoken.Jwts.parser()
                    .verifyWith(rsaKeys.getPublicKey())
                    .build()
                    .parseSignedClaims(token);
            String purpose = jws.getPayload().get("purpose", String.class);
            if (!expectedPurpose.equals(purpose)) {
                throw new InvalidCredentialsException();
            }
            return UUID.fromString(jws.getPayload().getSubject());
        } catch (InvalidCredentialsException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCredentialsException();
        }
    }

    private TokenResponse issueTokens(User user, String deviceId) {
        boolean isNewDevice = refreshTokenService.registerDeviceAndCheckIfNew(user.getId(), deviceId);
        if (isNewDevice) {
            // Real deployments should turn this into a "new device signed in"
            // email/push to the user - see RefreshTokenService's javadoc for
            // why that wiring isn't included here.
            log.warn("New device login for user {} (device={})", user.getId(), deviceId);
        }
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.issue(user.getId(), deviceId);
        return new TokenResponse(accessToken, refreshToken, jwtService.getAccessTokenTtlSeconds(),
                user.getId(), user.getRole().name());
    }
}
