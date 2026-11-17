package com.neobank.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdGlobalFilterTest {

    private final RequestIdGlobalFilter filter = new RequestIdGlobalFilter();

    private String run(MockServerHttpRequest request, AtomicReference<ServerWebExchange> forwarded, MockServerWebExchange[] holder) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        holder[0] = exchange;
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return ex.getResponse().setComplete();
        };
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        return forwarded.get().getRequest().getHeaders().getFirst(RequestIdGlobalFilter.HEADER);
    }

    @Test
    void generatesIdWhenAbsentAndReturnsItToClient() {
        MockServerWebExchange[] holder = new MockServerWebExchange[1];
        String forwardedId = run(MockServerHttpRequest.get("/api/v1/accounts").build(), new AtomicReference<>(), holder);

        assertThat(forwardedId).isNotBlank();
        assertThat(holder[0].getResponse().getHeaders().getFirst(RequestIdGlobalFilter.HEADER)).isEqualTo(forwardedId);
    }

    @Test
    void keepsWellFormedIncomingId() {
        MockServerWebExchange[] holder = new MockServerWebExchange[1];
        String forwardedId = run(MockServerHttpRequest.get("/x")
                .header(RequestIdGlobalFilter.HEADER, "abc12345-def6").build(), new AtomicReference<>(), holder);

        assertThat(forwardedId).isEqualTo("abc12345-def6");
        assertThat(holder[0].getResponse().getHeaders().getFirst(RequestIdGlobalFilter.HEADER)).isEqualTo("abc12345-def6");
    }

    @Test
    void replacesMalformedIncomingIdToPreventLogInjection() {
        MockServerWebExchange[] holder = new MockServerWebExchange[1];
        String forwardedId = run(MockServerHttpRequest.get("/x")
                .header(RequestIdGlobalFilter.HEADER, "bad value\r\nINJECTED").build(), new AtomicReference<>(), holder);

        assertThat(forwardedId).doesNotContain("INJECTED").matches("[A-Za-z0-9-]{36}");
    }

    @Test
    void runsBeforeOtherFilters() {
        assertThat(filter.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    }
}
