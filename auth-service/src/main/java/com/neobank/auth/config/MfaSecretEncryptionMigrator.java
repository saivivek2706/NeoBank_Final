package com.neobank.auth.config;

import com.neobank.auth.security.EncryptedMfaSecretConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * One-time, idempotent conversion of legacy plaintext TOTP secrets.
 * The converter's legacy read path allows the rows to be read before they
 * are rewritten. The compare predicate prevents overwriting a concurrent
 * enrollment update.
 */
@Component
@RequiredArgsConstructor
public class MfaSecretEncryptionMigrator implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final EncryptedMfaSecretConverter converter;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<LegacySecret> legacy = jdbcTemplate.query(
                "SELECT user_id, secret FROM mfa_credentials WHERE secret NOT LIKE 'enc:v1:%'",
                (rs, rowNum) -> new LegacySecret(rs.getObject("user_id", UUID.class), rs.getString("secret")));
        for (LegacySecret row : legacy) {
            String encrypted = converter.convertToDatabaseColumn(row.secret());
            jdbcTemplate.update(
                    "UPDATE mfa_credentials SET secret = ? WHERE user_id = ? AND secret = ?",
                    encrypted, row.userId(), row.secret());
        }
    }

    private record LegacySecret(UUID userId, String secret) {}
}
