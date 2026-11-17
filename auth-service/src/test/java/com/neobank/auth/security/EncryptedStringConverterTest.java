package com.neobank.auth.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedStringConverterTest {

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @Test
    void roundTrip_recoversOriginalPlaintext() {
        String original = "someone@neobank.dev";

        String encrypted = converter.convertToDatabaseColumn(original);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void encryptedValue_doesNotContainPlaintext() {
        String original = "sensitive-name@example.com";

        String encrypted = converter.convertToDatabaseColumn(original);

        assertThat(encrypted).doesNotContain(original);
    }

    @Test
    void sameInput_producesDifferentCiphertext_eachTime() {
        // Random IV per encryption is the whole point - identical plaintext
        // must never produce identical stored values, otherwise the ciphertext
        // itself would leak "these two rows have the same email".
        String original = "repeat@neobank.dev";

        String first = converter.convertToDatabaseColumn(original);
        String second = converter.convertToDatabaseColumn(original);

        assertThat(first).isNotEqualTo(second);
        assertThat(converter.convertToEntityAttribute(first)).isEqualTo(original);
        assertThat(converter.convertToEntityAttribute(second)).isEqualTo(original);
    }

    @Test
    void nullInput_staysNull_inBothDirections() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_rejectsTamperedCiphertext() {
        // GCM's authentication tag must reject any modification - flipping a
        // character in the stored value must not silently decrypt to garbage,
        // it must fail loudly.
        String encrypted = converter.convertToDatabaseColumn("tamper-test@neobank.dev");
        char[] chars = encrypted.toCharArray();
        chars[chars.length - 2] = chars[chars.length - 2] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> converter.convertToEntityAttribute(tampered));
    }
}
