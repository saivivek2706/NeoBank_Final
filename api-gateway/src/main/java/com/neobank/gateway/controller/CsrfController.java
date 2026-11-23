package com.neobank.gateway.controller;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Issues the CSRF token used by the browser for all gateway-routed mutations. */
@RestController
public class CsrfController {

    @GetMapping("/api/v1/csrf")
    public Mono<CsrfToken> csrf(ServerWebExchange exchange) {
        return exchange.getAttributeOrDefault(CsrfToken.class.getName(), Mono.empty());
    }
}
