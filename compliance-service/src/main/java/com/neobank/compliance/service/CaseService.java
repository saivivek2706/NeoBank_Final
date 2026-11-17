package com.neobank.compliance.service;

import com.neobank.compliance.config.ComplianceProperties;
import com.neobank.compliance.domain.AmlAlert;
import com.neobank.compliance.domain.AmlCase;
import com.neobank.compliance.domain.CaseEvent;
import com.neobank.compliance.domain.CaseEventType;
import com.neobank.compliance.domain.CaseStatus;
import com.neobank.compliance.domain.CaseType;
import com.neobank.compliance.domain.Disposition;
import com.neobank.compliance.domain.Severity;
import com.neobank.compliance.repository.AmlAlertRepository;
import com.neobank.compliance.repository.AmlCaseRepository;
import com.neobank.compliance.repository.CaseEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Case management: opens cases from alerts and screening hits, and drives them through the review workflow.
 *
 * Every state change appends a row to case_events (append-only), recording who did what and why.
 *
 * Decisions that need FOUR EYES (maker-checker) - filing a suspicious activity report, or clearing a sanctions hit that
 * already froze the account - require the case to be ESCALATED first and must be closed by a DIFFERENT person than the one
 * who escalated it.
 */
@Service
public class CaseService {

    public record CaseDetail(AmlCase amlCase, List<AmlAlert> alerts, List<CaseEvent> history) {
    }

    private static final Set<Disposition> MONITORING_DISPOSITIONS = EnumSet.of(
            Disposition.NO_SUSPICION, Disposition.FALSE_POSITIVE, Disposition.SAR_FILED, Disposition.ACCOUNT_RESTRICTED);
    private static final Set<Disposition> SANCTIONS_DISPOSITIONS = EnumSet.of(
            Disposition.FALSE_POSITIVE, Disposition.ACCOUNT_RESTRICTED);

    private final AmlCaseRepository caseRepository;
    private final CaseEventRepository eventRepository;
    private final AmlAlertRepository alertRepository;
    private final ComplianceActionPublisher actionPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final ComplianceProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public CaseService(AmlCaseRepository caseRepository,
                       CaseEventRepository eventRepository,
                       AmlAlertRepository alertRepository,
                       ComplianceActionPublisher actionPublisher,
                       ApplicationEventPublisher eventPublisher,
                       ComplianceProperties properties,
                       Clock clock,
                       MeterRegistry meterRegistry) {
        this.caseRepository = caseRepository;
        this.eventRepository = eventRepository;
        this.alertRepository = alertRepository;
        this.actionPublisher = actionPublisher;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        Gauge.builder("neobank.compliance.cases.open", caseRepository, AmlCaseRepository::countOpen)
                .description("Cases not yet closed").register(meterRegistry);
        Gauge.builder("neobank.compliance.cases.overdue", () -> caseRepository.countOverdue(clock.instant()))
                .description("Open cases past their SLA due date").register(meterRegistry);
    }

    // ------------------------------------------------------------------------------------------ opening

    /**
     * Opens a case for the subject account, or - if one is already open for that account and type - attaches to it
     * (raising its priority if the new finding is more severe). Either way the finding is recorded in the case history.
     */
    @Transactional
    public AmlCase openOrAttach(CaseType type, UUID accountId, Severity severity, String summary) {
        Instant now = clock.instant();
        AmlCase existing = caseRepository.findOpen(accountId, type).orElse(null);
        if (existing != null) {
            event(existing.getId(), CaseEventType.ALERT_ATTACHED, null, existing.getStatus(), existing.getStatus(), summary);
            if (existing.raisePriority(severity, now.plus(sla(severity)))) {
                event(existing.getId(), CaseEventType.PRIORITY_RAISED, null, existing.getStatus(), existing.getStatus(),
                        "Priority raised to " + severity);
                caseRepository.save(existing);
            }
            return existing;
        }

        String number = String.format("AML-%d-%06d", now.atZone(ZoneOffset.UTC).getYear(), caseRepository.nextCaseSequence());
        AmlCase created = caseRepository.save(AmlCase.open(number, accountId, type, severity, truncate(summary, 500), now,
                now.plus(sla(severity))));
        event(created.getId(), CaseEventType.OPENED, null, null, CaseStatus.OPEN, summary);
        meterRegistry.counter("neobank.compliance.cases.opened", "type", type.name(), "priority", severity.name()).increment();
        return created;
    }

    @Transactional
    public void attachAlert(AmlAlert alert, AmlCase amlCase) {
        alert.attachTo(amlCase.getId());
        alertRepository.save(alert);
    }

    /** Records that compliance asked for the subject account to be frozen because of this case. */
    @Transactional
    public void requestFreeze(AmlCase amlCase, UUID actor, String reason) {
        if (!amlCase.isAccountFrozen()) {
            amlCase.markAccountFrozen();
            caseRepository.save(amlCase);
        }
        actionPublisher.freeze(amlCase.getSubjectAccountId(), amlCase.getId(), reason);
        event(amlCase.getId(), CaseEventType.ACCOUNT_FREEZE_REQUESTED, actor, amlCase.getStatus(), amlCase.getStatus(), reason);
    }

    @Transactional
    public void recordSystemEvent(UUID caseId, CaseEventType type, String note) {
        AmlCase c = load(caseId);
        event(caseId, type, null, c.getStatus(), c.getStatus(), note);
    }

    // ------------------------------------------------------------------------------------------ workflow

    @Transactional
    public AmlCase assign(UUID caseId, UUID actor, UUID assignee) {
        AmlCase c = load(caseId);
        CaseStatus from = c.getStatus();
        c.assignTo(assignee);
        caseRepository.save(c);
        event(caseId, CaseEventType.ASSIGNED, actor, from, c.getStatus(), "Assigned to " + assignee);
        return c;
    }

    @Transactional
    public AmlCase addNote(UUID caseId, UUID actor, String note) {
        requireNote(note);
        AmlCase c = load(caseId);
        if (c.isClosed()) {
            throw new IllegalStateException("Case " + c.getCaseNumber() + " is closed");
        }
        event(caseId, CaseEventType.NOTE, actor, c.getStatus(), c.getStatus(), note.trim());
        return c;
    }

    @Transactional
    public AmlCase escalate(UUID caseId, UUID actor, String note) {
        requireNote(note);
        AmlCase c = load(caseId);
        CaseStatus from = c.getStatus();
        c.escalate(actor);
        caseRepository.save(c);
        event(caseId, CaseEventType.ESCALATED, actor, from, CaseStatus.ESCALATED, note.trim());
        return c;
    }

    @Transactional
    public AmlCase close(UUID caseId, UUID actor, Disposition disposition, String note) {
        requireNote(note);
        AmlCase c = load(caseId);
        if (c.isClosed()) {
            throw new IllegalStateException("Case " + c.getCaseNumber() + " is already closed");
        }
        Set<Disposition> allowed = c.getCaseType() == CaseType.SANCTIONS_SCREENING ? SANCTIONS_DISPOSITIONS : MONITORING_DISPOSITIONS;
        if (!allowed.contains(disposition)) {
            throw new IllegalArgumentException(disposition + " is not a valid outcome for a " + c.getCaseType() + " case; allowed: " + allowed);
        }
        if (needsFourEyes(c, disposition)) {
            if (c.getStatus() != CaseStatus.ESCALATED) {
                throw new IllegalStateException(disposition + " requires the case to be escalated first (maker-checker)");
            }
            if (actor.equals(c.getEscalatedBy())) {
                throw new IllegalStateException("Four-eyes rule: " + disposition + " must be approved by someone other than the user who escalated the case");
            }
        }

        CaseStatus from = c.getStatus();
        c.close(actor, disposition, clock.instant());
        caseRepository.save(c);
        event(caseId, CaseEventType.CLOSED, actor, from, CaseStatus.CLOSED, disposition + ": " + note.trim());

        if (c.getCaseType() == CaseType.TRANSACTION_MONITORING && disposition == Disposition.ACCOUNT_RESTRICTED) {
            requestFreeze(c, actor, "Account restricted on closure of " + c.getCaseNumber());
        }
        eventPublisher.publishEvent(new CaseClosedEvent(c.getId(), c.getSubjectAccountId(), c.getCaseType(), disposition, actor, note.trim()));
        meterRegistry.counter("neobank.compliance.cases.closed", "type", c.getCaseType().name(), "disposition", disposition.name()).increment();
        return c;
    }

    /** Decisions that must be made by two different people. */
    static boolean needsFourEyes(AmlCase c, Disposition disposition) {
        if (disposition == Disposition.SAR_FILED) {
            return true;
        }
        return c.getCaseType() == CaseType.SANCTIONS_SCREENING && disposition == Disposition.FALSE_POSITIVE && c.isAccountFrozen();
    }

    // ------------------------------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public CaseDetail detail(UUID caseId) {
        AmlCase c = load(caseId);
        return new CaseDetail(c, alertRepository.findByCaseIdOrderByCreatedAtAsc(caseId), eventRepository.findByCaseIdOrderByCreatedAtAsc(caseId));
    }

    @Transactional(readOnly = true)
    public Page<AmlCase> list(CaseStatus status, UUID assignee, Pageable pageable) {
        if (assignee != null) {
            return caseRepository.findOpenAssignedTo(assignee, pageable);
        }
        return status == null ? caseRepository.findAllOpen(pageable) : caseRepository.findByStatus(status, pageable);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private AmlCase load(UUID caseId) {
        return caseRepository.findById(caseId).orElseThrow(() -> new NoSuchElementException("Case " + caseId + " not found"));
    }

    private void event(UUID caseId, CaseEventType type, UUID actor, CaseStatus from, CaseStatus to, String note) {
        eventRepository.save(CaseEvent.of(caseId, type, actor, from, to, truncate(note, 1000)));
    }

    private Duration sla(Severity priority) {
        ComplianceProperties.Cases cases = properties.getCases();
        return switch (priority) {
            case CRITICAL -> cases.getSlaCritical();
            case HIGH -> cases.getSlaHigh();
            case MEDIUM -> cases.getSlaMedium();
            case LOW -> cases.getSlaLow();
        };
    }

    private static void requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A note explaining the decision is required");
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
