package com.neobank.auth.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class BlindIndexServiceTest {

    private final BlindIndexService service =
            new BlindIndexService(Base64.getEncoder().encodeToString("a-test-hmac-key-that-is-long-enough".getBytes()));

    @Test
    void hashEmail_isDeterministic() {
        assertThat(service.hashEmail("someone@neobank.dev"))
                .isEqualTo(service.hashEmail("someone@neobank.dev"));
    }

    @Test
    void hashEmail_isCaseAndWhitespaceInsensitive() {
        // A blind index that's case-sensitive would let "User@x.com" and
        // "user@x.com" register as two different accounts with the same
        // real inbox - normalize before hashing so lookups match how emails
        // actually work.
        String base = service.hashEmail("someone@neobank.dev");

        assertThat(service.hashEmail("Someone@Neobank.Dev")).isEqualTo(base);
        assertThat(service.hashEmail("  someone@neobank.dev  ")).isEqualTo(base);
    }

    @Test
    void hashEmail_differsForDifferentEmails() {
        assertThat(service.hashEmail("alice@neobank.dev"))
                .isNotEqualTo(service.hashEmail("bob@neobank.dev"));
    }

    @Test
    void hashEmail_producesFixedLengthHex() {
        // HmacSHA256 -> 32 bytes -> 64 hex characters, regardless of input length.
        assertThat(service.hashEmail("a@b.co")).hasSize(64);
        assertThat(service.hashEmail("a.very.long.email.address@some-long-domain.example.com")).hasSize(64);
    }
}
