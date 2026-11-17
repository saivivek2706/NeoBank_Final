package com.neobank.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id. Keeps a well-formed X-Request-Id supplied by the caller (e.g. nginx's
 * $request_id) or mints a UUID, forwards it to the downstream service - whose RequestIdFilter puts it in every
 * log line - and returns it to the client, so a user-reported failure can be traced across all services with
 * one grep. Malformed values are discarded so the header can't be used for log injection.
 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();

        ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, requestId)).build();

        // set() just before the response is committed so the downstream service's own copy of the header
        // can't end up duplicated next to ours.
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, requestId);
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
