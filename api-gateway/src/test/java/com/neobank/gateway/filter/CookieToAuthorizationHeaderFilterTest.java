package com.neobank.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CookieToAuthorizationHeaderFilterTest {

    private final CookieToAuthorizationHeaderFilter filter = new CookieToAuthorizationHeaderFilter();

    private String forwardedAuthorization(MockServerHttpRequest request) {
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            seen.set(ex);
            return Mono.empty();
        };
        StepVerifier.create(filter.filter(MockServerWebExchange.from(request), chain)).verifyComplete();
        return seen.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    }

    @Test
    void convertsAccessTokenCookieIntoBearerHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts")
                .cookie(new HttpCookie("access_token", "jwt-value")).build();

        assertThat(forwardedAuthorization(request)).isEqualTo("Bearer jwt-value");
    }

    @Test
    void explicitAuthorizationHeaderWinsOverCookie() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer from-header")
                .cookie(new HttpCookie("access_token", "from-cookie")).build();

        assertThat(forwardedAuthorization(request)).isEqualTo("Bearer from-header");
    }

    @Test
    void noCookieAndNoHeaderLeavesRequestUnauthenticated() {
        assertThat(forwardedAuthorization(MockServerHttpRequest.get("/api/v1/accounts").build())).isNull();
    }

    @Test
    void ignoresUnrelatedCookies() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/x")
                .cookie(new HttpCookie("theme", "dark")).build();

        assertThat(forwardedAuthorization(request)).isNull();
    }
}
