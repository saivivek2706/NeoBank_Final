package com.neobank.auth.domain;

import com.neobank.auth.security.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Encrypted at rest (AES-256-GCM, see EncryptedStringConverter) - a random
     * IV means this column can't be searched or uniqued by equality, which is
     * exactly why emailHash exists below.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 500)
    private String email;

    /** Deterministic HMAC blind index of the normalized email - see BlindIndexService. This, not `email`, is what login/registration actually query and unique-constrain on. */
    @Column(nullable = false, unique = true, length = 64)
    private String emailHash;

    @Column(nullable = false)
    private String passwordHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 500)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private Instant createdAt;

    /** Consecutive failed login attempts since the last successful login. Resets to 0 on success. */
    @Column(nullable = false)
    private int failedLoginAttempts;

    /** If set and in the future, the account is locked out and login must be rejected regardless of password. */
    @Column
    private Instant lockedUntil;

    /** Public no-argument constructor required by JPA/MongoDB. */
    public User() {
    }

    public UUID getId() {
        return this.id;
    }

    private void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return this.email;
    }

    public String getEmailHash() {
        return this.emailHash;
    }

    public String getPasswordHash() {
        return this.passwordHash;
    }

    public String getFullName() {
        return this.fullName;
    }

    public Role getRole() {
        return this.role;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public int getFailedLoginAttempts() {
        return this.failedLoginAttempts;
    }

    public Instant getLockedUntil() {
        return this.lockedUntil;
    }

    private void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public static User register(String email, String emailHash, String passwordHash, String fullName) {
        User user = new User();
        user.setEmail(email);
        user.setEmailHash(emailHash);
        user.setPasswordHash(passwordHash);
        user.setFullName(fullName);
        user.setRole(Role.CUSTOMER);
        user.setEnabled(true);
        user.setCreatedAt(Instant.now());
        user.setFailedLoginAttempts(0);
        return user;
    }

    /** Used only by AdminUserSeeder to provision the demo admin account - real admin promotion should go through an explicit, audited admin action, not a public API. */
    public static User registerAdmin(String email, String emailHash, String passwordHash, String fullName) {
        User user = register(email, emailHash, passwordHash, fullName);
        user.setRole(Role.ADMIN);
        return user;
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    /** Called after a failed password check. Locks the account once the threshold is reached. */
    public void registerFailedLogin(int maxAttempts, Duration lockoutDuration) {
        this.failedLoginAttempts = this.failedLoginAttempts + 1;
        if (this.failedLoginAttempts >= maxAttempts) {
            this.lockedUntil = Instant.now().plus(lockoutDuration);
        }
    }

    public void registerSuccessfulLogin() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }
}
