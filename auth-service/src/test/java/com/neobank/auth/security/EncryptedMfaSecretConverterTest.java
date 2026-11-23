package com.neobank.auth.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedMfaSecretConverterTest {

    private final EncryptedMfaSecretConverter converter = new EncryptedMfaSecretConverter();

    @Test
    void roundTrip_recoversOriginalSecret() {
        String secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
        String encrypted = converter.convertToDatabaseColumn(secret);

        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(converter.convertToEntityAttribute(encrypted)).isEqualTo(secret);
    }

    @Test
    void encryptingSameSecretProducesDifferentCiphertext() {
        String secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";

        assertThat(converter.convertToDatabaseColumn(secret))
                .isNotEqualTo(converter.convertToDatabaseColumn(secret));
    }

    @Test
    void legacyPlaintextCanBeReadForOneTimeMigration() {
        String legacy = "JBSWY3DPEHPK3PXP";

        assertThat(converter.convertToEntityAttribute(legacy)).isEqualTo(legacy);
    }

    @Test
    void nullValuesRemainNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
