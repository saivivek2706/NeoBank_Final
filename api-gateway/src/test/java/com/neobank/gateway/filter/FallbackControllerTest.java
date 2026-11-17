package com.neobank.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    @Test
    void everyFallbackReturns503NamingTheUnavailableService() {
        StepVerifier.create(controller.transactionFallback()).assertNext(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).containsEntry("status", 503);
            assertThat((String) response.getBody().get("message")).contains("transaction-service");
        }).verifyComplete();
    }

    @Test
    void eachServiceHasItsOwnFallback() {
        StepVerifier.create(controller.authFallback())
                .assertNext(r -> assertThat((String) r.getBody().get("message")).contains("auth-service")).verifyComplete();
        StepVerifier.create(controller.accountFallback())
                .assertNext(r -> assertThat((String) r.getBody().get("message")).contains("account-service")).verifyComplete();
        StepVerifier.create(controller.notificationFallback())
                .assertNext(r -> assertThat((String) r.getBody().get("message")).contains("notification-service")).verifyComplete();
        StepVerifier.create(controller.fraudFallback())
                .assertNext(r -> assertThat((String) r.getBody().get("message")).contains("fraud-service")).verifyComplete();
        StepVerifier.create(controller.auditFallback())
                .assertNext(r -> assertThat((String) r.getBody().get("message")).contains("audit-service")).verifyComplete();
        StepVerifier.create(controller.complianceFallback())
                .assertNext(r -> assertThat((String) r.getBody().get("message")).contains("compliance-service")).verifyComplete();
    }
}
