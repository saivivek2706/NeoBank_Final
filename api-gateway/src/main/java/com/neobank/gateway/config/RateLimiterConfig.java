package com.neobank.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Rate-limits login/register by client IP, backed by Redis so the limit is
 * shared correctly across every gateway replica (not just per-instance).
 * This is the primary defense against credential stuffing / brute force
 * sprayed across many accounts; the per-account lockout in auth-service is
 * the complementary defense against many guesses against ONE account.
 *
 * Uses X-Forwarded-For when present (gateway sits behind a load balancer/
 * ingress in real deployments) and falls back to the socket address
 * otherwise. In production, configure your ingress/LB to set X-Forwarded-For
 * and strip any client-supplied value first, or this header becomes spoofable.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver clientIpKeyResolver() {
        return exchange -> {
            String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return Mono.just(forwardedFor.split(",")[0].trim());
            }
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            String ip = remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
            return Mono.just(ip);
        };
    }
}
