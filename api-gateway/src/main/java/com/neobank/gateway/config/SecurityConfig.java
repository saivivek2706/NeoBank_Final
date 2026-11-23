package com.neobank.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * The gateway validates JWTs too (not just each downstream service). This is
 * "defense in depth": it lets us reject unauthenticated traffic at the edge
 * before it even reaches a backend service, while each service still
 * independently validates (never trust a network hop blindly, even an internal one).
 *
 * Verification uses auth-service's published public key (JWKS), never a
 * shared secret - the gateway (like every other service) is capable of
 * checking a token's signature but not of forging one.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${security.jwt.jwks-uri}")
    private String jwksUri;

    @Value("${security.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            // The gateway converts the HttpOnly access cookie into a bearer header,
            // so every browser mutation must carry a matching CSRF cookie/header.
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeExchange(exchange -> exchange
                    .pathMatchers("/api/v1/auth/**", "/api/v1/csrf", "/actuator/**", "/.well-known/**",
                            "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**", "/docs/**").permitAll()
                    // WebSocket upgrade requests can't carry an Authorization header from
                    // browser WebSocket clients, and our access_token cookie IS sent on the
                    // upgrade request - downstream services authenticate the WS session
                    // using that cookie/first-message token themselves. Left un-permitAll
                    // would require reworking the WS handshake auth; tracked as a
                    // Stage-2/3 item alongside device binding, not silently left insecure -
                    // downstream WS handlers still require a valid token before accepting
                    // any subscription (see transaction-service / fraud-service WS config).
                    .pathMatchers("/ws/**").permitAll()
                    .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder())));
        return http.build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Strict allow-list, never "*" - required because allowCredentials(true)
        // means any origin on the list can make authenticated (cookie-bearing)
        // requests. Configure via CORS_ALLOWED_ORIGINS (comma-separated) per
        // environment; never widen this to a wildcard pattern.
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Headers a browser client is allowed to read from cross-origin responses.
        config.setExposedHeaders(List.of("X-Request-Id", "Idempotent-Replayed"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
