package com.neobank.eureka;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.context.ApplicationContext;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/** Boots the real registry and checks the endpoints the rest of the platform (and Docker's HEALTHCHECK) depend on. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EurekaServerApplicationTest {

    @Autowired private TestRestTemplate rest;

    @Test
    void registryStartsAndServesAnEmptyApplicationList() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = rest.exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("applications");
    }

    @Test
    void healthEndpointIsExposedForContainerHealthchecks() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusEndpointIsExposed() {
        ResponseEntity<String> response =
                rest.getForEntity("/actuator/prometheus", String.class);

        System.out.println("Prometheus HTTP Status: "
                + response.getStatusCode());

        System.out.println("Prometheus Response Body: "
                + response.getBody());

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("Prometheus endpoint response: %s", response.getBody())
                .isTrue();

        assertThat(response.getBody())
                .contains("jvm_memory_used_bytes");
    }
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void diagnosePrometheusConfiguration() {
        String[] registryBeans =
                applicationContext.getBeanNamesForType(
                        PrometheusMeterRegistry.class
                );

        System.out.println("PROMETHEUS REGISTRY BEANS: "
                + java.util.Arrays.toString(registryBeans));

        String[] endpointBeans =
                applicationContext.getBeanNamesForAnnotation(
                        org.springframework.boot.actuate.endpoint.annotation.Endpoint.class
                );

        System.out.println("ACTUATOR ENDPOINT BEANS: "
                + java.util.Arrays.toString(endpointBeans));
    }

}
