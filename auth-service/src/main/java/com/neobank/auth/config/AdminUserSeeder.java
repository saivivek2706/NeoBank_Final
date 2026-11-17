package com.neobank.auth.config;

import com.neobank.auth.domain.User;
import com.neobank.auth.repository.UserRepository;
import com.neobank.auth.security.BlindIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps the FIRST admin account, and only when an operator explicitly supplies credentials via
 * ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD (scripts/bootstrap-env.sh generates a random password
 * into .env for local use; in a real environment these come from a secrets manager for the initial
 * deployment and are removed afterwards).
 *
 * There is intentionally no built-in default credential: if either value is unset, nothing is seeded.
 * The account goes through the normal User.registerAdmin(...) / repository path, so email and full_name
 * are AES-GCM encrypted with a blind-index hash exactly like any real signup.
 *
 * Idempotent: if an account with that email already exists it is left untouched (the password in the
 * environment is NOT re-applied on later startups - rotate it through the application instead).
 */
@Component
@Slf4j
public class AdminUserSeeder implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final BlindIndexService blindIndexService;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public AdminUserSeeder(UserRepository userRepository,
                           BlindIndexService blindIndexService,
                           PasswordEncoder passwordEncoder,
                           @Value("${security.bootstrap-admin.email:}") String adminEmail,
                           @Value("${security.bootstrap-admin.password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.blindIndexService = blindIndexService;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            log.info("ADMIN_BOOTSTRAP_EMAIL / ADMIN_BOOTSTRAP_PASSWORD not set - no admin account seeded");
            return;
        }
        if (adminPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "ADMIN_BOOTSTRAP_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        String emailHash = blindIndexService.hashEmail(adminEmail);
        if (userRepository.existsByEmailHash(emailHash)) {
            return;
        }
        User admin = User.registerAdmin(adminEmail, emailHash, passwordEncoder.encode(adminPassword), "NeoBank Admin");
        userRepository.save(admin);
        log.info("Bootstrapped admin account {}", adminEmail);
    }
}
