package com.neobank.transaction.controller;

import com.neobank.transaction.client.AccountClient;
import com.neobank.transaction.domain.Transaction;
import com.neobank.transaction.dto.TransactionResponse;
import com.neobank.transaction.dto.TransferRequest;
import com.neobank.transaction.repository.TransactionRepository;
import com.neobank.transaction.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Every endpoint here enforces object-level authorization - a customer can
 * only transact against / view accounts and transactions they're a party to,
 * verified against account-service (the source of truth for account
 * ownership) rather than trusting ids supplied in the request on their own.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Initiate transfers (asynchronous saga) and read their status")
public class TransactionController {

    private final TransferService transferService;
    private final TransactionRepository transactionRepository;
    private final AccountClient accountClient;

    @PostMapping("/transfer")
    @Operation(summary = "Start a transfer",
            description = "Asynchronous: returns 202 immediately while the saga runs. The Idempotency-Key header is required; "
                    + "retrying with the same key returns the original transaction (header Idempotent-Replayed: true) "
                    + "instead of moving money twice. The same key with a different body returns 422.")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @Parameter(description = "Unique key (1-100 chars) per logical transfer; reuse it only to retry the SAME transfer", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        UUID callerUserId = UUID.fromString(jwt.getSubject());
        String bearerToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);

        TransferService.TransferResult result;
        try {
            result = transferService.initiateTransfer(request, callerUserId, bearerToken, idempotencyKey);
        } catch (DataIntegrityViolationException raced) {
            // Two requests with the same key hit different replicas at the same moment; the database's
            // unique index let exactly one through. The loser answers from the winner's transaction.
            result = transferService.replayExisting(request, callerUserId, idempotencyKey);
        }

        // 202 Accepted, not 201/200: the transfer is asynchronous - the saga is
        // still in flight. Clients should poll GET /{id} or subscribe over
        // WebSocket to /topic/transactions/{id} for the final outcome.
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(TransactionResponse.from(result.transaction()));
    }

    @GetMapping("/{id}")
    public TransactionResponse getById(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                        HttpServletRequest httpRequest) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found: " + id));
        requireCallerIsParty(tx, httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        return TransactionResponse.from(tx);
    }

    @GetMapping("/account/{accountId}")
    public List<TransactionResponse> getForAccount(@PathVariable UUID accountId, HttpServletRequest httpRequest) {
        accountClient.verifyCallerOwnsAccount(accountId, httpRequest.getHeader(HttpHeaders.AUTHORIZATION));
        return transactionRepository.findByFromAccountIdOrToAccountIdOrderByCreatedAtDesc(accountId, accountId)
                .stream().map(TransactionResponse::from).toList();
    }

    /** A transaction can be viewed by either party to it (sender or recipient), not only the sender. */
    private void requireCallerIsParty(Transaction tx, String bearerToken) {
        try {
            accountClient.verifyCallerOwnsAccount(tx.getFromAccountId(), bearerToken);
        } catch (AccessDeniedException senderDenied) {
            accountClient.verifyCallerOwnsAccount(tx.getToAccountId(), bearerToken);
        }
    }
}
