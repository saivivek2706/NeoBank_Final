package com.neobank.account.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;


/**
 * Verifies incoming access tokens using auth-service's public key, fetched
 * from its JWKS endpoint (/.well-known/jwks.json) and cached by
 * NimbusJwtDecoder. This service can verify a token's signature but holds
 * nothing capable of forging one - only auth-service has the private key.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${security.jwt.jwks-uri}")
    private String jwksUri;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder())));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // Verifies with auth-service's PUBLIC key only (fetched + cached from
        // JWKS) - this service can check a token's signature but never forge one.
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }
}
