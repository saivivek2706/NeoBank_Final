package com.neobank.account.controller;

import com.neobank.account.domain.Account;
import com.neobank.account.dto.AccountResponse;
import com.neobank.account.dto.CreateAccountRequest;
import com.neobank.account.security.CurrentUser;
import com.neobank.account.service.AccountService;
import com.neobank.account.service.HoldService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Every endpoint here enforces object-level authorization: a customer can
 * only see/act on accounts they own, identified by comparing the
 * authenticated caller's user id (the JWT `sub` claim) against
 * Account.ownerId - never trusting an id supplied in the request path/query
 * on its own. ADMIN-role callers (fraud/support tooling) can access any
 * account. Without this, a valid login for ANY customer would be enough to
 * read or transact against every account in the bank, since account/
 * transaction ids are just UUIDs anyone could enumerate or otherwise obtain.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final com.neobank.account.repository.LedgerEntryRepository ledgerEntryRepository;
    private final com.neobank.account.service.LedgerReconciliationService ledgerReconciliationService;
    private final HoldService holdService;

    /**
     * Demo-only switch. In production an opening balance means money arrived from outside the bank, so only an
     * operator (ADMIN) may record one; customers always open at zero and are funded by an incoming payment.
     * docker-compose turns this on so the demo and the e2e test can fund their own accounts.
     */
    @Value("${accounts.self-service-funding:false}")
    private boolean selfServiceFunding;

    @PostMapping
    @RateLimiter(name = "accountCreation")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody CreateAccountRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        CurrentUser caller = CurrentUser.from(jwt);
        if (!caller.isAdmin() && !caller.id().equals(request.ownerId())) {
            throw new AccessDeniedException("You can only open accounts for yourself");
        }
        boolean fundsAccount = request.openingBalance() != null && request.openingBalance().signum() > 0;
        if (fundsAccount && !caller.isAdmin() && !selfServiceFunding) {
            throw new AccessDeniedException("Accounts are opened empty; fund the account with an incoming payment");
        }
        Account account = accountService.openAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    @GetMapping
    public java.util.List<AccountResponse> listByOwner(@RequestParam UUID ownerId,
                                                         @AuthenticationPrincipal Jwt jwt) {
        CurrentUser caller = CurrentUser.from(jwt);
        if (!caller.isAdmin() && !caller.id().equals(ownerId)) {
            throw new AccessDeniedException("You can only list your own accounts");
        }
        return accountService.getByOwnerId(ownerId).stream().map(AccountResponse::fromMasked).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse getById(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Account account = accountService.getById(id);
        requireOwnerOrAdmin(account, jwt);
        return AccountResponse.from(account);
    }

    @GetMapping("/by-number/{accountNumber}")
    public AccountResponse getByAccountNumber(@PathVariable String accountNumber, @AuthenticationPrincipal Jwt jwt) {
        Account account = accountService.getByAccountNumber(accountNumber);
        requireOwnerOrAdmin(account, jwt);
        return AccountResponse.from(account);
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<Object> getBalance(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Account account = accountService.getById(id);
        requireOwnerOrAdmin(account, jwt);
        java.math.BigDecimal held = holdService.heldAmount(account.getId());
        return ResponseEntity.ok(java.util.Map.of(
                "accountId", account.getId(),
                "balance", account.getBalance(),
                "heldAmount", held,
                "availableBalance", account.getBalance().subtract(held),
                "currency", account.getCurrency()
        ));
    }

    /** Paginated transaction history for this account, sourced from the append-only ledger rather than any derived/cached view. */
    @GetMapping("/{id}/ledger")
    public org.springframework.data.domain.Page<com.neobank.account.dto.LedgerEntryResponse> getLedger(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            org.springframework.data.domain.Pageable pageable) {
        Account account = accountService.getById(id);
        requireOwnerOrAdmin(account, jwt);
        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(id, pageable)
                .map(com.neobank.account.dto.LedgerEntryResponse::from);
    }

    /** Recomputes the account's balance purely from the ledger and compares it to the cached value - an audit tool, not something the UI calls on every page load. */
    @GetMapping("/{id}/reconcile")
    public com.neobank.account.dto.ReconciliationResponse reconcile(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Account account = accountService.getById(id);
        requireOwnerOrAdmin(account, jwt);
        return ledgerReconciliationService.reconcile(id);
    }

    /**
     * Internal, service-to-service ownership check used by transaction-service
     * before it starts a transfer saga: it calls GET /{id} with the caller's
     * own bearer token forwarded, and a 403 here is exactly what stops it from
     * ever moving money out of an account that doesn't belong to the caller.
     */
    private void requireOwnerOrAdmin(Account account, Jwt jwt) {
        CurrentUser caller = CurrentUser.from(jwt);
        if (!caller.isAdmin() && !caller.id().equals(account.getOwnerId())) {
            throw new AccessDeniedException("You do not have access to this account");
        }
    }
}
