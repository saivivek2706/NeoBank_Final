package com.neobank.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    @Test
    void keysOnFirstForwardedForAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/login")
                .header("X-Forwarded-For", "203.0.113.7, 10.0.0.2").build());

        StepVerifier.create(config.clientIpKeyResolver().resolve(exchange)).expectNext("203.0.113.7").verifyComplete();
    }

    @Test
    void fallsBackToUnknownWhenNoAddressIsAvailable() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/login").build());

        StepVerifier.create(config.clientIpKeyResolver().resolve(exchange)).expectNext("unknown").verifyComplete();
    }
}
