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
import org.springframework.beans.factory.annotation.Autowired;
import com.neobank.account.client.CustomerKycClient;
import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    @Autowired(required = false)
    private CustomerKycClient customerKycClient;

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
        if (!caller.isAdmin() && customerKycClient != null) customerKycClient.requireVerified(jwt.getTokenValue());
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

    @GetMapping(value = "/{id}/statement", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> statement(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) throws Exception {
        Account account = accountService.getById(id);
        requireOwnerOrAdmin(account, jwt);
        var entries = ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(id,
                org.springframework.data.domain.PageRequest.of(0, 1000)).getContent().stream()
                .filter(e -> from == null || !e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().isBefore(from))
                .filter(e -> to == null || !e.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate().isAfter(to))
                .toList();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document pdf = new Document();
        PdfWriter.getInstance(pdf, output);
        pdf.open();
        pdf.add(new Paragraph("NeoBank - Account Statement"));
        pdf.add(new Paragraph("Account: " + account.getAccountNumber() + " | Holder: " + account.getOwnerName()));
        pdf.add(new Paragraph("Period: " + (from == null ? "Beginning" : from) + " to " + (to == null ? "Present" : to)));
        PdfPTable table = new PdfPTable(5);
        for (String heading : new String[]{"Date (UTC)", "Description", "Type", "Amount", "Balance"}) table.addCell(new Phrase(heading));
        for (var entry : entries) {
            table.addCell(entry.getCreatedAt().toString());
            table.addCell(entry.getDescription());
            table.addCell(entry.getEntryType().name());
            table.addCell(entry.getAmount().toPlainString());
            table.addCell(entry.getBalanceAfter().toPlainString());
        }
        pdf.add(table);
        pdf.close();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename("statement-" + id + ".pdf").build().toString())
                .body(output.toByteArray());
    }

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
