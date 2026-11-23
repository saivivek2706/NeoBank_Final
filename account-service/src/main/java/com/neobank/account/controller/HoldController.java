package com.neobank.account.controller;

import com.neobank.account.domain.Account;
import com.neobank.account.dto.HoldDtos.CaptureHoldRequest;
import com.neobank.account.dto.HoldDtos.HoldResponse;
import com.neobank.account.dto.HoldDtos.PlaceHoldRequest;
import com.neobank.account.security.CurrentUser;
import com.neobank.account.service.AccountService;
import com.neobank.account.service.HoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Authorization holds. Placing, releasing and capturing are operator/processor actions (ADMIN); an account's owner can
 * only LIST the holds on their own account, so a customer can never reserve - or free - their own money.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/holds")
@RequiredArgsConstructor
@Tag(name = "Holds", description = "Reserve part of an account's balance (authorization holds)")
public class HoldController {

    private final HoldService holdService;
    private final AccountService accountService;

    @PostMapping
    @Operation(summary = "Place a hold (ADMIN)", description = "Idempotent per (account, reference). Reduces the AVAILABLE balance until released, captured or expired.")
    public ResponseEntity<HoldResponse> place(@PathVariable UUID accountId, @Valid @RequestBody PlaceHoldRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        Duration ttl = request.ttlMinutes() == null ? null : Duration.ofMinutes(request.ttlMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).body(HoldResponse.from(
                holdService.place(accountId, request.amount(), request.reference(), request.reason(), ttl)));
    }

    @GetMapping
    @Operation(summary = "List the account's active holds (owner or ADMIN)")
    public List<HoldResponse> list(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        Account account = accountService.getById(accountId);
        CurrentUser caller = CurrentUser.from(jwt);
        if (!caller.isAdmin() && !caller.id().equals(account.getOwnerId())) {
            throw new AccessDeniedException("You do not have access to this account");
        }
        return holdService.activeHolds(accountId).stream().map(HoldResponse::from).toList();
    }

    @PostMapping("/{holdId}/release")
    @Operation(summary = "Release a hold (ADMIN)", description = "Frees the reservation; no money moves. Idempotent.")
    public HoldResponse release(@PathVariable UUID accountId, @PathVariable UUID holdId, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return HoldResponse.from(holdService.release(accountId, holdId));
    }

    @PostMapping("/{holdId}/capture")
    @Operation(summary = "Capture a hold (ADMIN)", description = "Turns the hold into a real debit posted to the ledger. Omit amount to capture it all; a smaller amount releases the remainder.")
    public HoldResponse capture(@PathVariable UUID accountId, @PathVariable UUID holdId,
                                @Valid @RequestBody(required = false) CaptureHoldRequest request, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return HoldResponse.from(holdService.capture(accountId, holdId, request == null ? null : request.amount()));
    }

    private void requireAdmin(Jwt jwt) {
        if (!CurrentUser.from(jwt).isAdmin()) {
            throw new AccessDeniedException("Only an administrator can manage holds");
        }
    }
}
