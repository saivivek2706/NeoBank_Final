package com.neobank.auth.controller;

import com.neobank.auth.security.RsaKeyProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes only the PUBLIC key, in standard JWKS format, so every other
 * service can verify access tokens without ever holding (or being able to
 * derive) the private signing key. This endpoint must stay unauthenticated -
 * that's how JWKS is meant to work - but it never exposes anything an
 * attacker could use to sign their own tokens.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final RsaKeyProperties keys;

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        RSAKey jwk = new RSAKey.Builder(keys.getPublicKey())
                .keyID(keys.getKeyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build();
        return new JWKSet(jwk).toJSONObject();
    }
}
