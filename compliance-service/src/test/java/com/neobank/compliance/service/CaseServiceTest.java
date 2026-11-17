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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T09:00:00Z");

    @Mock private AmlCaseRepository caseRepository;
    @Mock private CaseEventRepository eventRepository;
    @Mock private AmlAlertRepository alertRepository;
    @Mock private ComplianceActionPublisher actionPublisher;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private CaseService service;

    private final UUID account = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID analyst = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CaseService(caseRepository, eventRepository, alertRepository, actionPublisher, eventPublisher,
                new ComplianceProperties(), Clock.fixed(NOW, ZoneOffset.UTC), meters);
        lenient().when(caseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(caseRepository.nextCaseSequence()).thenReturn(1L);
    }

    private AmlCase openCase(CaseType type, Severity priority) {
        AmlCase c = AmlCase.open("AML-2026-000001", account, type, priority, "summary", NOW, NOW.plus(Duration.ofDays(7)));
        lenient().when(caseRepository.findById(caseId)).thenReturn(Optional.of(c));
        return c;
    }

    private List<CaseEvent> recordedEvents(int expected) {
        ArgumentCaptor<CaseEvent> captor = ArgumentCaptor.forClass(CaseEvent.class);
        verify(eventRepository, org.mockito.Mockito.times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    // ---------------------------------------------------------------- opening / attaching

    @Test
    void openOrAttach_opensANumberedCaseWithAPrioritySla() {
        when(caseRepository.findOpen(account, CaseType.TRANSACTION_MONITORING)).thenReturn(Optional.empty());

        AmlCase c = service.openOrAttach(CaseType.TRANSACTION_MONITORING, account, Severity.HIGH, "3 transfers under threshold");

        assertThat(c.getCaseNumber()).isEqualTo("AML-2026-000001");
        assertThat(c.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(c.getPriority()).isEqualTo(Severity.HIGH);
        assertThat(c.getDueAt()).isEqualTo(NOW.plus(Duration.ofHours(72)));      // HIGH = 72h
        assertThat(recordedEvents(1)).singleElement().satisfies(e -> {
            assertThat(e.getEventType()).isEqualTo(CaseEventType.OPENED);
            assertThat(e.getActor()).isNull();                                    // opened by the system
        });
        assertThat(meters.get("neobank.compliance.cases.opened").tag("priority", "HIGH").counter().count()).isEqualTo(1.0);
    }

    @Test
    void openOrAttach_slaDependsOnPriority() {
        when(caseRepository.findOpen(any(), any())).thenReturn(Optional.empty());

        assertThat(service.openOrAttach(CaseType.SANCTIONS_SCREENING, account, Severity.CRITICAL, "s").getDueAt())
                .isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(service.openOrAttach(CaseType.SANCTIONS_SCREENING, account, Severity.LOW, "s").getDueAt())
                .isEqualTo(NOW.plus(Duration.ofDays(14)));
    }

    @Test
    void openOrAttach_attachesToTheExistingOpenCase_insteadOfOpeningASecond() {
        AmlCase existing = AmlCase.open("AML-2026-000009", account, CaseType.TRANSACTION_MONITORING, Severity.MEDIUM, "s", NOW.minusSeconds(60),
                NOW.plus(Duration.ofDays(7)));
        when(caseRepository.findOpen(account, CaseType.TRANSACTION_MONITORING)).thenReturn(Optional.of(existing));

        AmlCase result = service.openOrAttach(CaseType.TRANSACTION_MONITORING, account, Severity.HIGH, "another finding");

        assertThat(result).isSameAs(existing);
        assertThat(existing.getPriority()).isEqualTo(Severity.HIGH);               // raised
        assertThat(existing.getDueAt()).isEqualTo(NOW.plus(Duration.ofHours(72))); // and the deadline moved earlier
        assertThat(recordedEvents(2)).extracting(CaseEvent::getEventType)
                .containsExactly(CaseEventType.ALERT_ATTACHED, CaseEventType.PRIORITY_RAISED);
        verify(caseRepository, never()).nextCaseSequence();
    }

    @Test
    void openOrAttach_neverLowersAPriorityOrPushesTheDeadlineOut() {
        AmlCase existing = AmlCase.open("AML-2026-000009", account, CaseType.TRANSACTION_MONITORING, Severity.CRITICAL, "s", NOW.minusSeconds(60),
                NOW.plus(Duration.ofHours(24)));
        when(caseRepository.findOpen(account, CaseType.TRANSACTION_MONITORING)).thenReturn(Optional.of(existing));

        service.openOrAttach(CaseType.TRANSACTION_MONITORING, account, Severity.LOW, "minor finding");

        assertThat(existing.getPriority()).isEqualTo(Severity.CRITICAL);
        assertThat(existing.getDueAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(recordedEvents(1)).singleElement().satisfies(e -> assertThat(e.getEventType()).isEqualTo(CaseEventType.ALERT_ATTACHED));
    }

    @Test
    void attachAlert_linksTheAlertToTheCase() {
        AmlCase c = openCase(CaseType.TRANSACTION_MONITORING, Severity.MEDIUM);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", caseId);
        AmlAlert alert = AmlAlert.of("LARGE_TRANSACTION", account, UUID.randomUUID(), Severity.MEDIUM, 60, "s", "{}", "k");

        service.attachAlert(alert, c);

        assertThat(alert.getCaseId()).isEqualTo(caseId);
        verify(alertRepository).save(alert);
    }

    // ---------------------------------------------------------------- workflow

    @Test
    void assign_movesAnOpenCaseToInReview_andRecordsWho() {
        AmlCase c = openCase(CaseType.TRANSACTION_MONITORING, Severity.MEDIUM);

        service.assign(caseId, analyst, analyst);

        assertThat(c.getStatus()).isEqualTo(CaseStatus.IN_REVIEW);
        assertThat(c.getAssignee()).isEqualTo(analyst);
        assertThat(recordedEvents(1)).singleElement().satisfies(e -> {
            assertThat(e.getEventType()).isEqualTo(CaseEventType.ASSIGNED);
            assertThat(e.getActor()).isEqualTo(analyst);
            assertThat(e.getFromStatus()).isEqualTo(CaseStatus.OPEN);
            assertThat(e.getToStatus()).isEqualTo(CaseStatus.IN_REVIEW);
        });
    }

    @Test
    void reassigningAnEscalatedCase_keepsItEscalated() {
        AmlCase c = openCase(CaseType.TRANSACTION_MONITORING, Severity.HIGH);
        c.escalate(analyst);

        service.assign(caseId, analyst, approver);

        assertThat(c.getStatus()).isEqualTo(CaseStatus.ESCALATED);
        assertThat(c.getAssignee()).isEqualTo(approver);
    }

    @Test
    void notesAndEscalationRequireAnExplanation() {
        assertThatThrownBy(() -> service.addNote(caseId, analyst, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.escalate(caseId, analyst, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.close(caseId, analyst, Disposition.NO_SUSPICION, "")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(caseRepository);
    }

    @Test
    void escalate_recordsTheMaker_andCannotBeDoneTwice() {
        AmlCase c = openCase(CaseType.TRANSACTION_MONITORING, Severity.HIGH);

        service.escalate(caseId, analyst, "needs a second opinion");

        assertThat(c.getStatus()).isEqualTo(CaseStatus.ESCALATED);
        assertThat(c.getEscalatedBy()).isEqualTo(analyst);
        assertThatThrownBy(() -> service.escalate(caseId, approver, "again")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownCaseIsNotFound() {
        when(caseRepository.findById(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(caseId, analyst, analyst)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.detail(caseId)).isInstanceOf(NoSuchElementException.class);
    }

    // ---------------------------------------------------------------- closing and four-eyes

    @Test
    void anOrdinaryOutcomeNeedsNoSecondPerson() {
        AmlCase c = openCase(CaseType.TRANSACTION_MONITORING, Severity.MEDIUM);

        service.close(caseId, analyst, Disposition.NO_SUSPICION, "reviewed: salary payments, documented");

        assertThat(c.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(c.getDisposition()).isEqualTo(Disposition.NO_SUSPICION);
        assertThat(c.getClosedBy()).isEqualTo(analyst);
        assertThat(c.getClosedAt()).isEqualTo(NOW);
        verify(eventPublisher).publishEvent(any(CaseClosedEvent.class));
    }

    @Test
    void fileASarOnlyFromAnEscalatedCase() {
        openCase(CaseType.TRANSACTION_MONITORING, Severity.HIGH);

        assertThatThrownBy(() -> service.close(caseId, analyst, Disposition.SAR_FILED, "filed with the FIU"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("escalated first");
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void theUserWhoEscalatedCannotAlsoApproveTheSar() {
        AmlCase c = openCase(CaseType.TRANSACTION_MONITORING, Severity.HIGH);
        c.escalate(analyst);

        assertThatThrownBy(() -> service.close(caseId, analyst, Disposition.SAR_FILED, "self-approval"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Four-eyes");
        assertThat(c.isClosed()).isFalse();
    }

    @Test
    void aDifferentUserCanApproveTheSar() {
        AmlCase c = openCase(CaseType.TRANSACTION_MONITORING, Severity.HIGH);
        c.escalate(analyst);

        service.close(caseId, approver, Disposition.SAR_FILED, "approved and filed, ref FIU-2026-77");

        assertThat(c.getDisposition()).isEqualTo(Disposition.SAR_FILED);
        ArgumentCaptor<CaseClosedEvent> closed = ArgumentCaptor.forClass(CaseClosedEvent.class);
        verify(eventPublisher).publishEvent(closed.capture());
        assertThat(closed.getValue().actor()).isEqualTo(approver);
        assertThat(closed.getValue().disposition()).isEqualTo(Disposition.SAR_FILED);
    }

    @Test
    void restrictingTheAccountOnAMonitoringCase_asksForAFreeze() {
        AmlCase c = openCase(CaseType.TRANSACTION_MONITORING, Severity.HIGH);

        service.close(caseId, analyst, Disposition.ACCOUNT_RESTRICTED, "mule activity confirmed");

        assertThat(c.isAccountFrozen()).isTrue();
        verify(actionPublisher).freeze(eq(account), any(), org.mockito.ArgumentMatchers.contains("restricted"));
    }

    @Test
    void clearingASanctionsHitThatFroze_theAccount_needsFourEyes() {
        AmlCase c = openCase(CaseType.SANCTIONS_SCREENING, Severity.CRITICAL);
        c.markAccountFrozen();

        assertThatThrownBy(() -> service.close(caseId, analyst, Disposition.FALSE_POSITIVE, "different person, DOB differs"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("escalated first");

        c.escalate(analyst);
        assertThatThrownBy(() -> service.close(caseId, analyst, Disposition.FALSE_POSITIVE, "self-approval"))
                .hasMessageContaining("Four-eyes");

        service.close(caseId, approver, Disposition.FALSE_POSITIVE, "DOB and nationality differ - verified");
        assertThat(c.getDisposition()).isEqualTo(Disposition.FALSE_POSITIVE);
    }

    @Test
    void clearingAPotentialMatchThatDidNotFreeze_needsNoSecondPerson() {
        AmlCase c = openCase(CaseType.SANCTIONS_SCREENING, Severity.HIGH);

        service.close(caseId, analyst, Disposition.FALSE_POSITIVE, "name only coincidentally similar");

        assertThat(c.isClosed()).isTrue();
    }

    @Test
    void aDispositionMustFitTheCaseType() {
        openCase(CaseType.SANCTIONS_SCREENING, Severity.HIGH);

        assertThatThrownBy(() -> service.close(caseId, analyst, Disposition.SAR_FILED, "n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a valid outcome");
        assertThatThrownBy(() -> service.close(caseId, analyst, Disposition.NO_SUSPICION, "n"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClosedCaseCannotBeChangedAgain() {
        AmlCase c = openCase(CaseType.TRANSACTION_MONITORING, Severity.LOW);
        c.close(analyst, Disposition.NO_SUSPICION, NOW);

        assertThatThrownBy(() -> service.close(caseId, analyst, Disposition.NO_SUSPICION, "again")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.addNote(caseId, analyst, "late note")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.assign(caseId, analyst, analyst)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.escalate(caseId, analyst, "n")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestFreeze_persistsTheFlagOnce_andRepublishesTheIdempotentCommand() {
        AmlCase c = openCase(CaseType.SANCTIONS_SCREENING, Severity.CRITICAL);

        service.requestFreeze(c, null, "strong sanctions match");
        service.requestFreeze(c, null, "strong sanctions match");

        assertThat(c.isAccountFrozen()).isTrue();
        verify(actionPublisher, org.mockito.Mockito.times(2)).freeze(eq(account), any(), eq("strong sanctions match"));   // command is idempotent downstream
        verify(caseRepository, org.mockito.Mockito.times(1)).save(c);                                                    // the flag is persisted once
    }

    @Test
    void needsFourEyes_isDecidedByDispositionAndFreezeState() {
        AmlCase monitoring = AmlCase.open("n", account, CaseType.TRANSACTION_MONITORING, Severity.LOW, "s", NOW, NOW);
        AmlCase sanctions = AmlCase.open("n", account, CaseType.SANCTIONS_SCREENING, Severity.LOW, "s", NOW, NOW);

        assertThat(CaseService.needsFourEyes(monitoring, Disposition.SAR_FILED)).isTrue();
        assertThat(CaseService.needsFourEyes(monitoring, Disposition.NO_SUSPICION)).isFalse();
        assertThat(CaseService.needsFourEyes(sanctions, Disposition.FALSE_POSITIVE)).isFalse();
        sanctions.markAccountFrozen();
        assertThat(CaseService.needsFourEyes(sanctions, Disposition.FALSE_POSITIVE)).isTrue();
        assertThat(CaseService.needsFourEyes(sanctions, Disposition.ACCOUNT_RESTRICTED)).isFalse();
    }

    @Test
    void overdueIsRelativeToTheDueDate() {
        AmlCase c = AmlCase.open("n", account, CaseType.TRANSACTION_MONITORING, Severity.LOW, "s", NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)));

        assertThat(c.isOverdue(NOW)).isTrue();
        c.close(analyst, Disposition.NO_SUSPICION, NOW);
        assertThat(c.isOverdue(NOW)).isFalse();
    }
}
