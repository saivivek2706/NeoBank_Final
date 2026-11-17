package com.neobank.compliance.dto;

import com.neobank.compliance.domain.AmlAlert;
import com.neobank.compliance.domain.AmlCase;
import com.neobank.compliance.domain.CaseEvent;
import com.neobank.compliance.domain.CaseEventType;
import com.neobank.compliance.domain.CaseStatus;
import com.neobank.compliance.domain.CaseType;
import com.neobank.compliance.domain.Disposition;
import com.neobank.compliance.domain.PartyStatus;
import com.neobank.compliance.domain.ScreenedParty;
import com.neobank.compliance.domain.ScreeningOutcome;
import com.neobank.compliance.domain.Severity;
import com.neobank.compliance.domain.WatchlistEntry;
import com.neobank.compliance.domain.WatchlistVersion;
import com.neobank.compliance.service.CaseService;
import com.neobank.compliance.service.WatchlistIndex;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public final class ComplianceDtos {

    private ComplianceDtos() {
    }

    /** Stable page envelope (Spring's PageImpl is not meant to be serialised directly). */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
            return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }

    // ---- cases
    public record CaseSummary(UUID id, String caseNumber, UUID subjectAccountId, CaseType caseType, CaseStatus status,
                              Severity priority, UUID assignee, boolean accountFrozen, String summary,
                              Disposition disposition, Instant openedAt, Instant dueAt, Instant closedAt, boolean overdue) {
        public static CaseSummary from(AmlCase c, Instant now) {
            return new CaseSummary(c.getId(), c.getCaseNumber(), c.getSubjectAccountId(), c.getCaseType(), c.getStatus(),
                    c.getPriority(), c.getAssignee(), c.isAccountFrozen(), c.getSummary(), c.getDisposition(),
                    c.getOpenedAt(), c.getDueAt(), c.getClosedAt(), c.isOverdue(now));
        }
    }

    public record AlertResponse(UUID id, String ruleCode, UUID accountId, UUID transactionId, Severity severity, int score,
                                String summary, String details, Instant createdAt) {
        public static AlertResponse from(AmlAlert a) {
            return new AlertResponse(a.getId(), a.getRuleCode(), a.getAccountId(), a.getTransactionId(), a.getSeverity(),
                    a.getScore(), a.getSummary(), a.getDetails(), a.getCreatedAt());
        }
    }

    public record CaseEventResponse(UUID id, CaseEventType eventType, UUID actor, CaseStatus fromStatus, CaseStatus toStatus,
                                    String note, Instant createdAt) {
        public static CaseEventResponse from(CaseEvent e) {
            return new CaseEventResponse(e.getId(), e.getEventType(), e.getActor(), e.getFromStatus(), e.getToStatus(),
                    e.getNote(), e.getCreatedAt());
        }
    }

    public record CaseDetailResponse(CaseSummary summary, UUID escalatedBy, List<AlertResponse> alerts, List<CaseEventResponse> history) {
        public static CaseDetailResponse from(CaseService.CaseDetail d, Instant now) {
            return new CaseDetailResponse(CaseSummary.from(d.amlCase(), now), d.amlCase().getEscalatedBy(),
                    d.alerts().stream().map(AlertResponse::from).toList(),
                    d.history().stream().map(CaseEventResponse::from).toList());
        }
    }

    public record AssignRequest(UUID assignee) {
    }

    public record NoteRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record CloseRequest(@NotNull Disposition disposition, @NotBlank @Size(max = 1000) String note) {
    }

    // ---- sanctions
    public record ScreenNameRequest(@NotBlank @Size(max = 255) String name) {
    }

    public record MatchResponse(UUID entryId, String listSource, String matchedName, String via, double score) {
        public static MatchResponse from(WatchlistIndex.Match m) {
            return new MatchResponse(m.entryId(), m.listSource(), m.matchedName(), m.via(), m.score());
        }
    }

    public record AdHocScreeningResponse(ScreeningOutcome outcome, double topScore, List<MatchResponse> matches) {
    }

    public record PartyResponse(UUID id, UUID accountId, UUID ownerId, String partyName, PartyStatus status, BigDecimal topScore,
                                boolean autoFrozen, Instant lastScreenedAt, Instant resolvedAt, String resolutionNote) {
        public static PartyResponse from(ScreenedParty p) {
            return new PartyResponse(p.getId(), p.getAccountId(), p.getOwnerId(), p.getPartyName(), p.getStatus(), p.getTopScore(),
                    p.isAutoFrozen(), p.getLastScreenedAt(), p.getResolvedAt(), p.getResolutionNote());
        }
    }

    public record WatchlistEntryResponse(UUID id, String listSource, String externalId, String primaryName, String country,
                                         String entityType, boolean active) {
        public static WatchlistEntryResponse from(WatchlistEntry e) {
            return new WatchlistEntryResponse(e.getId(), e.getListSource(), e.getExternalId(), e.getPrimaryName(), e.getCountry(),
                    e.getEntityType().name(), e.isActive());
        }
    }

    public record WatchlistVersionResponse(UUID id, UUID importedBy, Instant importedAt, String mode, int entryCount, int added,
                                           int changed, int deactivated, String checksum) {
        public static WatchlistVersionResponse from(WatchlistVersion v) {
            return new WatchlistVersionResponse(v.getId(), v.getImportedBy(), v.getImportedAt(), v.getMode().name(),
                    v.getEntryCount(), v.getAdded(), v.getChanged(), v.getDeactivated(), v.getChecksum());
        }
    }

    public record OverviewResponse(long openCases, long overdueCases, long openCritical, long openHigh, long partiesUnderReview,
                                   long alertsLast24h, long activeWatchlistEntries) {
    }
}
