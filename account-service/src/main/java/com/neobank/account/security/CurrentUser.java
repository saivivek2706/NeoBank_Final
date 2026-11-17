package com.neobank.account.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Every controller method that touches a specific account needs to know
 * WHO is asking, not just THAT they're authenticated - Spring Security's
 * oauth2ResourceServer only proves the token is valid, it doesn't enforce
 * that the caller owns the resource they're asking for. That check has to
 * happen explicitly per-request, which is what this + AccountOwnershipGuard
 * exist for.
 */
public record CurrentUser(UUID id, String role) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("role"));
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
