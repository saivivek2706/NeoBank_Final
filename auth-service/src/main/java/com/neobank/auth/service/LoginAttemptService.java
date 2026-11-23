package com.neobank.auth.service;

import com.neobank.auth.domain.User;
import com.neobank.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Commits login-attempt counters independently of the authentication request.
 * Authentication failures are represented by exceptions, which would
 * otherwise roll back the counter update in AuthService's transaction.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registerFailedAttempt(UUID userId, int maxAttempts, Duration lockoutDuration) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("User disappeared during login"));
        user.registerFailedLogin(maxAttempts, lockoutDuration);
        userRepository.save(user);
        return user.isLocked();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("User disappeared during login"));
        user.registerSuccessfulLogin();
        userRepository.save(user);
    }
}
