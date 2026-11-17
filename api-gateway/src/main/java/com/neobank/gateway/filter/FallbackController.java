package com.neobank.gateway.filter;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Served when a downstream service's circuit breaker is OPEN (i.e. it's been
 * failing enough that the gateway stops even trying to call it, to avoid
 * piling up latency/pressure on an already-struggling service). Returns fast
 * with a clear message instead of the client hanging on a timeout.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/auth")
    public Mono<ResponseEntity<Map<String, Object>>> authFallback() {
        return fallback("auth-service");
    }

    @RequestMapping("/account")
    public Mono<ResponseEntity<Map<String, Object>>> accountFallback() {
        return fallback("account-service");
    }

    @RequestMapping("/transaction")
    public Mono<ResponseEntity<Map<String, Object>>> transactionFallback() {
        return fallback("transaction-service");
    }

    @RequestMapping("/notification")
    public Mono<ResponseEntity<Map<String, Object>>> notificationFallback() {
        return fallback("notification-service");
    }

    @RequestMapping("/fraud")
    public Mono<ResponseEntity<Map<String, Object>>> fraudFallback() {
        return fallback("fraud-service");
    }

    @RequestMapping("/audit")
    public Mono<ResponseEntity<Map<String, Object>>> auditFallback() {
        return fallback("audit-service");
    }

    @RequestMapping("/compliance")
    public Mono<ResponseEntity<Map<String, Object>>> complianceFallback() {
        return fallback("compliance-service");
    }

    private Mono<ResponseEntity<Map<String, Object>>> fallback(String service) {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", 503,
                "error", "Service Unavailable",
                "message", service + " is temporarily unavailable, please try again shortly"
        )));
    }
}
