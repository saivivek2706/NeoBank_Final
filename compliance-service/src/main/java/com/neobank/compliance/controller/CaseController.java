package com.neobank.compliance.controller;

import com.neobank.compliance.domain.CaseStatus;
import com.neobank.compliance.dto.ComplianceDtos.AssignRequest;
import com.neobank.compliance.dto.ComplianceDtos.CaseDetailResponse;
import com.neobank.compliance.dto.ComplianceDtos.CaseSummary;
import com.neobank.compliance.dto.ComplianceDtos.CloseRequest;
import com.neobank.compliance.dto.ComplianceDtos.NoteRequest;
import com.neobank.compliance.dto.ComplianceDtos.PageResponse;
import com.neobank.compliance.service.CaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/compliance/cases")
@RequiredArgsConstructor
@Tag(name = "Cases", description = "AML and sanctions case management with maker-checker (four-eyes) approval - ADMIN only")
public class CaseController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CaseService caseService;
    private final Clock clock;

    @GetMapping
    @Operation(summary = "List cases", description = "Open cases by default (most urgent first). Filter by status, or by `assignee` (open cases of one reviewer).")
    public PageResponse<CaseSummary> list(@RequestParam(required = false) CaseStatus status,
                                          @RequestParam(required = false) UUID assignee,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.asc("dueAt"), Sort.Order.desc("priority")));
        return PageResponse.of(caseService.list(status, assignee, pageable), c -> CaseSummary.from(c, clock.instant()));
    }

    @GetMapping("/{caseId}")
    @Operation(summary = "A case with its alerts and full history")
    public CaseDetailResponse get(@PathVariable UUID caseId) {
        return CaseDetailResponse.from(caseService.detail(caseId), clock.instant());
    }

    @PostMapping("/{caseId}/assign")
    @Operation(summary = "Assign a case (defaults to yourself) - moves OPEN to IN_REVIEW")
    public CaseSummary assign(@PathVariable UUID caseId, @RequestBody(required = false) AssignRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        UUID assignee = request != null && request.assignee() != null ? request.assignee() : actor;
        return CaseSummary.from(caseService.assign(caseId, actor, assignee), clock.instant());
    }

    @PostMapping("/{caseId}/notes")
    @Operation(summary = "Add an investigation note")
    public CaseSummary note(@PathVariable UUID caseId, @Valid @RequestBody NoteRequest request, @AuthenticationPrincipal Jwt jwt) {
        return CaseSummary.from(caseService.addNote(caseId, actor(jwt), request.note()), clock.instant());
    }

    @PostMapping("/{caseId}/escalate")
    @Operation(summary = "Escalate for a second pair of eyes",
            description = "Required before a SAR_FILED outcome, or before clearing a sanctions hit that froze the account. "
                    + "The escalating user cannot also approve the closure.")
    public CaseSummary escalate(@PathVariable UUID caseId, @Valid @RequestBody NoteRequest request, @AuthenticationPrincipal Jwt jwt) {
        return CaseSummary.from(caseService.escalate(caseId, actor(jwt), request.note()), clock.instant());
    }

    @PostMapping("/{caseId}/close")
    @Operation(summary = "Close a case with a disposition and a note",
            description = "SAR_FILED, and FALSE_POSITIVE on an account compliance froze, need an ESCALATED case closed by a different user than the escalator (409 otherwise).")
    public CaseSummary close(@PathVariable UUID caseId, @Valid @RequestBody CloseRequest request, @AuthenticationPrincipal Jwt jwt) {
        return CaseSummary.from(caseService.close(caseId, actor(jwt), request.disposition(), request.note()), clock.instant());
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
