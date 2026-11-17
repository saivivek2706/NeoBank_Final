package com.neobank.auth.service;

import com.neobank.auth.dto.AuthDtos.TokenResponse;

/** Login either succeeds outright, or (if the account has MFA enabled) hands back a challenge to complete. */
public sealed interface LoginOutcome {
    record Success(TokenResponse tokens) implements LoginOutcome {}
    record MfaRequired(String challengeToken) implements LoginOutcome {}
}
