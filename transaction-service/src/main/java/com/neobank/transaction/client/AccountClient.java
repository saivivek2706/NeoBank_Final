package com.neobank.transaction.client;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Before starting a transfer saga, transaction-service must know the caller
 * actually owns the source account - otherwise any authenticated customer
 * could move money out of any account in the bank just by guessing/obtaining
 * its UUID. Rather than duplicating account-service's ownership logic here
 * (and risking it drifting out of sync), this simply calls account-service's
 * own authorized GET /accounts/{id} endpoint, forwarding the caller's own
 * bearer token unchanged. account-service's answer (200 = yours, 403 = not
 * yours, 404 = doesn't exist) becomes the single source of truth.
 */
@Component
@RequiredArgsConstructor
public class AccountClient {

    private final RestTemplate restTemplate;

    /** @param bearerToken the exact "Bearer xxx" value from the original inbound request */
    public void verifyCallerOwnsAccount(UUID accountId, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        // Keep the caller's request id on the service-to-service hop so one user action is greppable end to end.
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            headers.set("X-Request-Id", requestId);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            restTemplate.exchange("http://account-service/api/v1/accounts/{id}", HttpMethod.GET,
                    entity, Void.class, accountId);
        } catch (HttpClientErrorException.Forbidden e) {
            throw new AccessDeniedException("You do not have access to this account");
        } catch (HttpClientErrorException.NotFound e) {
            throw new NoSuchElementException("Account not found: " + accountId);
        }
    }
}
