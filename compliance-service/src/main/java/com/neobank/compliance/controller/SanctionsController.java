package com.neobank.compliance.controller;

import com.neobank.compliance.domain.PartyStatus;
import com.neobank.compliance.domain.WatchlistMode;
import com.neobank.compliance.dto.ComplianceDtos.AdHocScreeningResponse;
import com.neobank.compliance.dto.ComplianceDtos.MatchResponse;
import com.neobank.compliance.dto.ComplianceDtos.PageResponse;
import com.neobank.compliance.dto.ComplianceDtos.PartyResponse;
import com.neobank.compliance.dto.ComplianceDtos.ScreenNameRequest;
import com.neobank.compliance.dto.ComplianceDtos.WatchlistEntryResponse;
import com.neobank.compliance.dto.ComplianceDtos.WatchlistVersionResponse;
import com.neobank.compliance.repository.ScreenedPartyRepository;
import com.neobank.compliance.repository.WatchlistEntryRepository;
import com.neobank.compliance.repository.WatchlistVersionRepository;
import com.neobank.compliance.service.NameNormalizer;
import com.neobank.compliance.service.ScreeningService;
import com.neobank.compliance.service.WatchlistService;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/compliance")
@RequiredArgsConstructor
@Tag(name = "Sanctions screening", description = "Watchlist import, customer screening status and ad-hoc name checks - ADMIN only")
public class SanctionsController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WatchlistService watchlistService;
    private final ScreeningService screeningService;
    private final WatchlistEntryRepository entryRepository;
    private final WatchlistVersionRepository versionRepository;
    private final ScreenedPartyRepository partyRepository;

    @PostMapping(value = "/watchlist/import", consumes = "text/csv")
    @Operation(summary = "Import a sanctions list (CSV) and re-screen every customer",
            description = "Columns: list_source,external_id,name,aliases(|-separated),country,entity_type. mode=FULL (default) deactivates "
                    + "entries of the mentioned lists that are absent from the file; DELTA only adds/updates. Returns how many customers were newly flagged.")
    public WatchlistService.ImportResult importWatchlist(@RequestBody String csv,
                                                         @RequestParam(defaultValue = "FULL") WatchlistMode mode,
                                                         @AuthenticationPrincipal Jwt jwt) {
        return watchlistService.importCsv(csv, mode, UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/watchlist/versions")
    @Operation(summary = "Recent watchlist imports")
    public List<WatchlistVersionResponse> versions() {
        return versionRepository.findTop20ByOrderByImportedAtDesc().stream().map(WatchlistVersionResponse::from).toList();
    }

    @GetMapping("/watchlist/entries")
    @Operation(summary = "Search watchlist entries by name fragment")
    public PageResponse<WatchlistEntryResponse> entries(@RequestParam(defaultValue = "") String query,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by("primaryName"));
        return PageResponse.of(entryRepository.findByNormalizedNameContaining(NameNormalizer.normalize(query), pageable),
                WatchlistEntryResponse::from);
    }

    @PostMapping("/screening/name")
    @Operation(summary = "Screen a name against the active watchlists without storing anything")
    public AdHocScreeningResponse screenName(@Valid @RequestBody ScreenNameRequest request) {
        ScreeningService.AdHocResult result = screeningService.screenAdHoc(request.name());
        return new AdHocScreeningResponse(result.outcome(), result.topScore(), result.matches().stream().map(MatchResponse::from).toList());
    }

    @GetMapping("/screening/parties")
    @Operation(summary = "Screened customers, filtered by status (default POTENTIAL_MATCH: those awaiting a decision)")
    public PageResponse<PartyResponse> parties(@RequestParam(defaultValue = "POTENTIAL_MATCH") PartyStatus status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by(Sort.Order.desc("topScore")));
        return PageResponse.of(partyRepository.findByStatus(status, pageable), PartyResponse::from);
    }

    @GetMapping("/screening/parties/{accountId}")
    @Operation(summary = "Screening status of one account's owner")
    public PartyResponse party(@PathVariable UUID accountId) {
        return partyRepository.findByAccountId(accountId).map(PartyResponse::from)
                .orElseThrow(() -> new java.util.NoSuchElementException("Account " + accountId + " has not been screened"));
    }
}
