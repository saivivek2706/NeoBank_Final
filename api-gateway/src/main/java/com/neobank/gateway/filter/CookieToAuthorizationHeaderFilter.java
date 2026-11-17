package com.neobank.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * The browser never sees the access token as JS-readable text - it lives in
 * an httpOnly cookie. But Spring Security's oauth2ResourceServer (here, and
 * in every downstream service) expects a standard `Authorization: Bearer`
 * header. This filter bridges the two: if a request carries the access_token
 * cookie and no Authorization header of its own, it copies the cookie value
 * into the header before the request reaches routing/security.
 *
 * Runs at HIGHEST_PRECEDENCE so it executes before Spring Security's filter
 * chain evaluates the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CookieToAuthorizationHeaderFilter implements WebFilter {

    private static final String ACCESS_COOKIE = "access_token";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        if (headers.containsKey(HttpHeaders.AUTHORIZATION)) {
            // Caller already supplied a bearer token explicitly (e.g. a service-to-service
            // or mobile client that isn't using the cookie flow) - don't override it.
            return chain.filter(exchange);
        }

        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(ACCESS_COOKIE);
        if (cookie == null) {
            return chain.filter(exchange);
        }

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + cookie.getValue())))
                .build();
        return chain.filter(mutated);
    }
}
