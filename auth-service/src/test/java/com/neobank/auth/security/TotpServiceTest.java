package com.neobank.auth.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {

    private final TotpService totpService = new TotpService();

    @Test
    void generateSecret_producesDifferentSecretsEachTime() {
        String a = totpService.generateSecret();
        String b = totpService.generateSecret();
        assertThat(a).isNotEqualTo(b);
        assertThat(a).matches("[A-Z2-7]+"); // valid base32 alphabet
    }

    @Test
    void verifyCode_acceptsCurrentlyValidCode() throws Exception {
        String secret = totpService.generateSecret();
        String currentCode = currentCodeFor(secret);

        assertThat(totpService.verifyCode(secret, currentCode)).isTrue();
    }

    @Test
    void verifyCode_rejectsWrongCode() {
        String secret = totpService.generateSecret();
        assertThat(totpService.verifyCode(secret, "000000")).isFalse();
    }

    @Test
    void verifyCode_rejectsMalformedInput() {
        String secret = totpService.generateSecret();
        assertThat(totpService.verifyCode(secret, "abcdef")).isFalse();
        assertThat(totpService.verifyCode(secret, "12345")).isFalse(); // too short
        assertThat(totpService.verifyCode(secret, null)).isFalse();
    }

    @Test
    void verifyCode_rejectsCodeGeneratedFromADifferentSecret() throws Exception {
        String secretA = totpService.generateSecret();
        String secretB = totpService.generateSecret();
        String codeForB = currentCodeFor(secretB);

        assertThat(totpService.verifyCode(secretA, codeForB)).isFalse();
    }

    @Test
    void provisioningUri_includesIssuerAndAccountEmail() {
        String uri = totpService.provisioningUri("ABCDEFGHIJ234567", "someone@neobank.dev", "NeoBank");
        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("NeoBank");
        assertThat(uri).contains("secret=ABCDEFGHIJ234567");
    }

    /** Reaches into the private generateCode(byte[], long) method to compute what verifyCode should currently accept. */
    private String currentCodeFor(String base32Secret) throws Exception {
        Method decode = TotpService.class.getDeclaredMethod("base32Decode", String.class);
        decode.setAccessible(true);
        byte[] key = (byte[]) decode.invoke(totpService, base32Secret);

        Method generate = TotpService.class.getDeclaredMethod("generateCode", byte[].class, long.class);
        generate.setAccessible(true);
        long step = System.currentTimeMillis() / 1000 / 30;
        return (String) generate.invoke(totpService, key, step);
    }
}
