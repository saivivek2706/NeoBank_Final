package com.neobank.account.controller;

import com.neobank.account.dto.LedgerAdminDtos.BreakResponse;
import com.neobank.account.dto.LedgerAdminDtos.ResolveBreakRequest;
import com.neobank.account.dto.LedgerAdminDtos.RunDetail;
import com.neobank.account.dto.LedgerAdminDtos.RunSummary;
import com.neobank.account.dto.LedgerAdminDtos.StatementUploadRequest;
import com.neobank.account.security.CurrentUser;
import com.neobank.account.service.LedgerAuditService;
import com.neobank.account.service.StatementCsvParser;
import com.neobank.account.service.StatementReconciliationService;
import com.neobank.account.service.StatementReconciliationService.LineInput;
import com.neobank.account.service.StatementReconciliationService.RunReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Back-office finance endpoints: trial balance and external bank-statement reconciliation. ADMIN only. */
@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger (back office)", description = "Trial balance and reconciliation against external bank statements - ADMIN only")
public class LedgerAdminController {

    private final LedgerAuditService ledgerAuditService;
    private final StatementReconciliationService reconciliationService;

    @GetMapping("/trial-balance")
    @Operation(summary = "Trial balance and integrity checks",
            description = "Per-currency debit/credit totals, system-account balances (incl. money in SETTLEMENT), unbalanced journals, "
                    + "account drift and transfers stuck in settlement. `healthy` is true only if every check passes.")
    public LedgerAuditService.TrialBalance trialBalance(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return ledgerAuditService.trialBalance();
    }

    @PostMapping(value = "/statements", consumes = "application/json")
    @Operation(summary = "Upload a bank statement (JSON) and reconcile it against the ledger",
            description = "One statement per (source, date, currency); a second upload is rejected with 409. Every difference becomes a break.")
    public ResponseEntity<RunDetail> uploadJson(@Valid @RequestBody StatementUploadRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        String currency = request.currency().toUpperCase(Locale.ROOT);
        List<LineInput> lines = request.lines().stream()
                .map(l -> new LineInput(l.reference(), l.direction(), l.amount(), currency, l.description()))
                .toList();
        RunReport report = reconciliationService.reconcile(request.source(), request.statementDate(), currency, lines,
                CurrentUser.from(jwt).id());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(report));
    }

    @PostMapping(value = "/statements", consumes = "text/csv")
    @Operation(summary = "Upload a bank statement (CSV) and reconcile it against the ledger",
            description = "Body columns: reference,direction,amount,currency[,description]. direction is INFLOW or OUTFLOW from the bank's point of view.")
    public ResponseEntity<RunDetail> uploadCsv(@RequestParam String source,
                                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statementDate,
                                               @RequestParam String currency,
                                               @RequestBody String csv,
                                               @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        String ccy = currency.toUpperCase(Locale.ROOT);
        RunReport report = reconciliationService.reconcile(source, statementDate, ccy,
                StatementCsvParser.parse(csv), CurrentUser.from(jwt).id());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(report));
    }

    @GetMapping("/reconciliation-runs")
    @Operation(summary = "Recent reconciliation runs")
    public List<RunSummary> runs(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return reconciliationService.recentRuns().stream().map(RunSummary::from).toList();
    }

    @GetMapping("/reconciliation-runs/{runId}")
    @Operation(summary = "A reconciliation run with its breaks")
    public RunDetail run(@PathVariable UUID runId, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return toDetail(reconciliationService.getRun(runId));
    }

    @PostMapping("/reconciliation-breaks/{breakId}/resolve")
    @Operation(summary = "Resolve a break with an explanatory note")
    public BreakResponse resolve(@PathVariable UUID breakId, @Valid @RequestBody ResolveBreakRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        return BreakResponse.from(reconciliationService.resolveBreak(breakId, CurrentUser.from(jwt).id(), request.note()));
    }

    private static RunDetail toDetail(RunReport report) {
        return new RunDetail(RunSummary.from(report.run()), report.breaks().stream().map(BreakResponse::from).toList());
    }

    private void requireAdmin(Jwt jwt) {
        if (!CurrentUser.from(jwt).isAdmin()) {
            throw new AccessDeniedException("Back-office ledger endpoints are restricted to administrators");
        }
    }
}
