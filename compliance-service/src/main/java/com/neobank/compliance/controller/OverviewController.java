package com.neobank.compliance.controller;

import com.neobank.compliance.domain.PartyStatus;
import com.neobank.compliance.domain.Severity;
import com.neobank.compliance.dto.ComplianceDtos.OverviewResponse;
import com.neobank.compliance.repository.AmlAlertRepository;
import com.neobank.compliance.repository.AmlCaseRepository;
import com.neobank.compliance.repository.ScreenedPartyRepository;
import com.neobank.compliance.repository.WatchlistEntryRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/compliance/overview")
@RequiredArgsConstructor
@Tag(name = "Overview", description = "Headline numbers for the compliance team - ADMIN only")
public class OverviewController {

    private final AmlCaseRepository caseRepository;
    private final AmlAlertRepository alertRepository;
    private final ScreenedPartyRepository partyRepository;
    private final WatchlistEntryRepository entryRepository;
    private final Clock clock;

    @GetMapping
    @Operation(summary = "Open and overdue cases, parties awaiting a decision, recent alert volume")
    public OverviewResponse overview() {
        return new OverviewResponse(
                caseRepository.countOpen(),
                caseRepository.countOverdue(clock.instant()),
                caseRepository.countOpenByPriority(Severity.CRITICAL),
                caseRepository.countOpenByPriority(Severity.HIGH),
                partyRepository.countByStatus(PartyStatus.POTENTIAL_MATCH),
                alertRepository.countByCreatedAtAfter(clock.instant().minus(Duration.ofHours(24))),
                entryRepository.countByActiveTrue());
    }
}
