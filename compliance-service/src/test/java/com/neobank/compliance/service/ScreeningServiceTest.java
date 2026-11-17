package com.neobank.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.compliance.config.ComplianceProperties;
import com.neobank.compliance.domain.AmlCase;
import com.neobank.compliance.domain.CaseType;
import com.neobank.compliance.domain.Disposition;
import com.neobank.compliance.domain.PartyStatus;
import com.neobank.compliance.domain.ScreenedParty;
import com.neobank.compliance.domain.ScreeningOutcome;
import com.neobank.compliance.domain.ScreeningResult;
import com.neobank.compliance.domain.ScreeningTrigger;
import com.neobank.compliance.domain.Severity;
import com.neobank.compliance.kafka.event.AccountOpenedEvent;
import com.neobank.compliance.repository.ProcessedEventRepository;
import com.neobank.compliance.repository.ScreenedPartyRepository;
import com.neobank.compliance.repository.ScreeningResultRepository;
import com.neobank.compliance.repository.WatchlistAliasRepository;
import com.neobank.compliance.repository.WatchlistEntryRepository;
import com.neobank.compliance.repository.WatchlistVersionRepository;
import com.neobank.compliance.service.WatchlistIndex.Candidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScreeningServiceTest {

    @Mock private ScreenedPartyRepository partyRepository;
    @Mock private ScreeningResultRepository resultRepository;
    @Mock private WatchlistVersionRepository versionRepository;
    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private CaseService caseService;
    @Mock private ComplianceActionPublisher actionPublisher;

    private final UUID entryId = UUID.randomUUID();
    private final UUID oldVersion = UUID.randomUUID();
    private final UUID newVersion = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();

    private ComplianceProperties properties;
    private WatchlistIndex index;
    private ScreeningService service;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        properties = new ComplianceProperties();
        index = new WatchlistIndex(mock(WatchlistEntryRepository.class), mock(WatchlistAliasRepository.class));
        index.replace(List.of(candidate(entryId, "OFAC_SDN", "Ivan Petrov", "PRIMARY", oldVersion)));
        service = new ScreeningService(partyRepository, resultRepository, versionRepository, processedEventRepository, index,
                caseService, actionPublisher, properties, new ObjectMapper(), meters);
        lenient().when(partyRepository.findByAccountId(account)).thenReturn(Optional.empty());
        lenient().when(partyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(versionRepository.findFirstByOrderByImportedAtDesc()).thenReturn(Optional.empty());
        lenient().when(caseService.openOrAttach(any(), any(), any(), anyString())).thenAnswer(inv ->
                AmlCase.open("AML-2026-000001", inv.getArgument(1), inv.getArgument(0), inv.getArgument(2), "s", Instant.now(), Instant.now()));
    }

    private static Candidate candidate(UUID entry, String source, String name, String via, UUID version) {
        return new Candidate(entry, source, name, NameNormalizer.normalize(name), via, version);
    }

    private ScreenedParty screen(String name) {
        return service.screenParty(account, owner, name, ScreeningTrigger.ONBOARDING, null);
    }

    // ---------------------------------------------------------------- outcomes

    @Test
    void anIdenticalNameIsAStrongMatch_theAccountIsFrozen_andACriticalCaseOpens() {
        ScreenedParty party = screen("IVAN PETROV");

        assertThat(party.getStatus()).isEqualTo(PartyStatus.POTENTIAL_MATCH);
        assertThat(party.isAutoFrozen()).isTrue();
        assertThat(party.getTopScore()).isEqualByComparingTo("1.0000");
        verify(caseService).openOrAttach(eq(CaseType.SANCTIONS_SCREENING), eq(account), eq(Severity.CRITICAL), contains("STRONG MATCH"));
        verify(caseService).requestFreeze(any(AmlCase.class), eq(null), contains("STRONG_MATCH"));
        assertThat(meters.get("neobank.sanctions.hits").tag("outcome", "STRONG_MATCH").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aCloseFuzzyMatchGoesToHumanReview_withoutFreezingByDefault() {
        ScreenedParty party = screen("Ivan Petrof");

        assertThat(party.getStatus()).isEqualTo(PartyStatus.POTENTIAL_MATCH);
        assertThat(party.isAutoFrozen()).isFalse();
        verify(caseService).openOrAttach(eq(CaseType.SANCTIONS_SCREENING), eq(account), eq(Severity.HIGH), contains("potential match"));
        verify(caseService, never()).requestFreeze(any(), any(), anyString());
        verifyNoInteractions(actionPublisher);
    }

    @Test
    void aFuzzyMatchFreezesWhenThePolicyIsSwitchedOn() {
        properties.getSanctions().setFreezeOnPotentialMatch(true);

        ScreenedParty party = screen("Ivan Petrof");

        assertThat(party.isAutoFrozen()).isTrue();
        verify(caseService).requestFreeze(any(AmlCase.class), eq(null), anyString());
    }

    @Test
    void aCleanNameOpensNothing_butTheRunIsStillRecordedAsEvidence() {
        ScreenedParty party = screen("Jane Doe");

        assertThat(party.getStatus()).isEqualTo(PartyStatus.CLEAR);
        assertThat(party.isAutoFrozen()).isFalse();
        verifyNoInteractions(caseService, actionPublisher);
        ArgumentCaptor<ScreeningResult> result = ArgumentCaptor.forClass(ScreeningResult.class);
        verify(resultRepository).save(result.capture());
        assertThat(result.getValue().getOutcome()).isEqualTo(ScreeningOutcome.CLEAR);
        assertThat(result.getValue().getTrigger()).isEqualTo(ScreeningTrigger.ONBOARDING);
        assertThat(result.getValue().getScreenedName()).isEqualTo("Jane Doe");
    }

    @Test
    void theEvidenceRecordsTheMatchesFound() {
        screen("Ivan Petrov");

        ArgumentCaptor<ScreeningResult> result = ArgumentCaptor.forClass(ScreeningResult.class);
        verify(resultRepository).save(result.capture());
        assertThat(result.getValue().getOutcome()).isEqualTo(ScreeningOutcome.STRONG_MATCH);
        assertThat(result.getValue().getMatches()).contains("OFAC_SDN").contains("Ivan Petrov").contains(entryId.toString());
    }

    @Test
    void aPartyAlreadyUnderReviewIsNotFlaggedTwice() {
        ScreenedParty existing = ScreenedParty.of(account, owner, "Ivan Petrov");
        existing.flag(new java.math.BigDecimal("1.0"), true);
        when(partyRepository.findByAccountId(account)).thenReturn(Optional.of(existing));

        service.screenParty(account, owner, "Ivan Petrov", ScreeningTrigger.WATCHLIST_UPDATE, newVersion);

        verifyNoInteractions(caseService, actionPublisher);   // the open case already covers it
    }

    @Test
    void aClearedCustomerIsOnlyReFlaggedByEntriesAddedInTheLatestImport() {
        ScreenedParty cleared = ScreenedParty.of(account, owner, "Ivan Petrov");
        cleared.clearAsFalsePositive(reviewer, "different person");
        when(partyRepository.findByAccountId(account)).thenReturn(Optional.of(cleared));

        // the same (old) listing matches again, but it was already reviewed: not re-flagged
        service.screenParty(account, owner, "Ivan Petrov", ScreeningTrigger.WATCHLIST_UPDATE, newVersion);
        assertThat(cleared.getStatus()).isEqualTo(PartyStatus.CLEARED_FALSE_POSITIVE);
        verifyNoInteractions(caseService);

        // a NEW entry in the latest import that matches IS flagged
        index.replace(List.of(candidate(entryId, "OFAC_SDN", "Ivan Petrov", "PRIMARY", oldVersion),
                candidate(UUID.randomUUID(), "UN", "Ivan Petrov", "PRIMARY", newVersion)));
        service.screenParty(account, owner, "Ivan Petrov", ScreeningTrigger.WATCHLIST_UPDATE, newVersion);
        assertThat(cleared.getStatus()).isEqualTo(PartyStatus.POTENTIAL_MATCH);
    }

    // ---------------------------------------------------------------- triggers

    @Test
    void accountOpenedEventsAreScreened_once() {
        UUID eventId = UUID.randomUUID();
        AccountOpenedEvent event = new AccountOpenedEvent(eventId, account, owner, "Ivan Petrov", "SGD", Instant.now());
        when(processedEventRepository.existsById(eventId)).thenReturn(false);

        service.onAccountOpened(event);

        verify(caseService).openOrAttach(eq(CaseType.SANCTIONS_SCREENING), eq(account), any(), anyString());
        verify(processedEventRepository).save(any());
    }

    @Test
    void aRedeliveredAccountOpenedEventIsIgnored() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsById(eventId)).thenReturn(true);

        service.onAccountOpened(new AccountOpenedEvent(eventId, account, owner, "Ivan Petrov", "SGD", Instant.now()));

        verifyNoInteractions(caseService, resultRepository, partyRepository);
    }

    @Test
    void rescreenAllReportsHowManyCustomersWereNewlyFlagged() {
        UUID otherAccount = UUID.randomUUID();
        ScreenedParty hit = ScreenedParty.of(account, owner, "Ivan Petrov");
        ScreenedParty clean = ScreenedParty.of(otherAccount, UUID.randomUUID(), "Jane Doe");
        when(partyRepository.findAll()).thenReturn(List.of(hit, clean));
        when(partyRepository.findByAccountId(account)).thenReturn(Optional.of(hit));
        when(partyRepository.findByAccountId(otherAccount)).thenReturn(Optional.of(clean));

        assertThat(service.rescreenAll(newVersion)).isEqualTo(1);
        assertThat(hit.getStatus()).isEqualTo(PartyStatus.POTENTIAL_MATCH);
        assertThat(clean.getStatus()).isEqualTo(PartyStatus.CLEAR);
    }

    @Test
    void anAdHocScreenStoresNothing() {
        ScreeningService.AdHocResult result = service.screenAdHoc("Ivan Petrov");

        assertThat(result.outcome()).isEqualTo(ScreeningOutcome.STRONG_MATCH);
        assertThat(result.matches()).hasSize(1);
        assertThat(service.screenAdHoc("Jane Doe").outcome()).isEqualTo(ScreeningOutcome.CLEAR);
        verifyNoInteractions(partyRepository, resultRepository, caseService);
    }

    // ---------------------------------------------------------------- case decisions applied to the party

    private ScreenedParty frozenParty() {
        ScreenedParty p = ScreenedParty.of(account, owner, "Ivan Petrov");
        p.flag(new java.math.BigDecimal("1.0"), true);
        when(partyRepository.findByAccountId(account)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void clearingAFrozenHitAsAFalsePositive_unfreezesTheAccount() {
        ScreenedParty p = frozenParty();
        UUID caseId = UUID.randomUUID();

        service.onCaseClosed(new CaseClosedEvent(caseId, account, CaseType.SANCTIONS_SCREENING, Disposition.FALSE_POSITIVE, reviewer, "different DOB"));

        assertThat(p.getStatus()).isEqualTo(PartyStatus.CLEARED_FALSE_POSITIVE);
        assertThat(p.isAutoFrozen()).isFalse();
        assertThat(p.getResolvedBy()).isEqualTo(reviewer);
        verify(actionPublisher).unfreeze(eq(account), eq(caseId), anyString());
    }

    @Test
    void confirmingAMatch_keepsTheAccountFrozen_andDoesNotRefreezeNeedlessly() {
        ScreenedParty p = frozenParty();

        service.onCaseClosed(new CaseClosedEvent(UUID.randomUUID(), account, CaseType.SANCTIONS_SCREENING, Disposition.ACCOUNT_RESTRICTED, reviewer, "confirmed"));

        assertThat(p.getStatus()).isEqualTo(PartyStatus.CONFIRMED_MATCH);
        verify(actionPublisher, never()).freeze(any(), any(), anyString());   // already frozen by compliance
    }

    @Test
    void confirmingAMatchThatWasNotFrozenYet_freezesIt() {
        ScreenedParty p = ScreenedParty.of(account, owner, "Ivan Petrof");
        p.flag(new java.math.BigDecimal("0.96"), false);
        when(partyRepository.findByAccountId(account)).thenReturn(Optional.of(p));

        service.onCaseClosed(new CaseClosedEvent(UUID.randomUUID(), account, CaseType.SANCTIONS_SCREENING, Disposition.ACCOUNT_RESTRICTED, reviewer, "confirmed"));

        assertThat(p.getStatus()).isEqualTo(PartyStatus.CONFIRMED_MATCH);
        assertThat(p.isAutoFrozen()).isTrue();
        verify(actionPublisher).freeze(eq(account), any(), anyString());
    }

    @Test
    void closingAMonitoringCaseDoesNotTouchSanctionsState() {
        service.onCaseClosed(new CaseClosedEvent(UUID.randomUUID(), account, CaseType.TRANSACTION_MONITORING, Disposition.FALSE_POSITIVE, reviewer, "n"));

        verifyNoInteractions(partyRepository, actionPublisher);
    }
}
