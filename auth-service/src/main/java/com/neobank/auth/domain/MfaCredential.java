package com.neobank.auth.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per user who has started/completed TOTP enrollment. Kept separate
 * from User (rather than a couple of extra columns there) so "not enrolled"
 * is simply "no row exists" instead of a handful of nullable fields, and so
 * the secret - genuinely sensitive, equivalent to a password - has its own
 * access surface.
 *
 * `enabled` stays false until the user proves they can generate a valid code
 * from the secret (mfa/enroll/verify) - otherwise a user who scans the QR
 * code wrong, or whose enrollment request is interrupted, would get locked
 * out of their own account on next login.
 */
@Entity
@Table(name = "mfa_credentials")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class MfaCredential {

    @Id
    private UUID userId;

    /**
     * TOTP secret, base32-encoded. Stored as plaintext in this dev/demo
     * database for simplicity; a real deployment must encrypt this column
     * at rest (e.g. via application-level envelope encryption with a KMS,
     * or transparent column encryption) since it's equivalent to a
     * permanent second password if it leaks.
     */
    @Column(nullable = false)
    private String secret;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private Instant createdAt;

    public static MfaCredential pendingEnrollment(UUID userId, String secret) {
        MfaCredential cred = new MfaCredential();
        cred.setUserId(userId);
        cred.setSecret(secret);
        cred.setEnabled(false);
        cred.setCreatedAt(Instant.now());
        return cred;
    }

    public void activate() {
        setEnabled(true);
    }
}
