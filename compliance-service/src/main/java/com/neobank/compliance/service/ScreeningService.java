package com.neobank.compliance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.compliance.config.ComplianceProperties;
import com.neobank.compliance.domain.AmlCase;
import com.neobank.compliance.domain.CaseEventType;
import com.neobank.compliance.domain.CaseType;
import com.neobank.compliance.domain.PartyStatus;
import com.neobank.compliance.domain.ProcessedEvent;
import com.neobank.compliance.domain.ScreenedParty;
import com.neobank.compliance.domain.ScreeningOutcome;
import com.neobank.compliance.domain.ScreeningResult;
import com.neobank.compliance.domain.ScreeningTrigger;
import com.neobank.compliance.domain.Severity;
import com.neobank.compliance.domain.WatchlistVersion;
import com.neobank.compliance.kafka.event.AccountOpenedEvent;
import com.neobank.compliance.repository.ProcessedEventRepository;
import com.neobank.compliance.repository.ScreenedPartyRepository;
import com.neobank.compliance.repository.ScreeningResultRepository;
import com.neobank.compliance.repository.WatchlistVersionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Customer sanctions screening.
 *
 * Every account owner is screened when the account opens (ONBOARDING) and again whenever a watchlist is imported
 * (WATCHLIST_UPDATE) - a customer who was clean yesterday can be listed today. All transfers on this platform are
 * between customers of the same bank, so screening the customer base against the list on those two triggers covers both
 * counterparties; there is deliberately no synchronous call in the payment path.
 *
 * Outcomes:
 *   STRONG_MATCH     the name is identical to a listed name (ignoring case, accents, punctuation, word order):
 *                    the account is FROZEN at once and a CRITICAL case opens.
 *   POTENTIAL_MATCH  a close fuzzy match: a HIGH case opens for a human to decide. Frozen only if
 *                    compliance.sanctions.freeze-on-potential-match is on.
 *   CLEAR            nothing to do (the run is still recorded as evidence).
 *
 * A reviewer later confirms (account stays restricted) or clears it as a false positive (account unfrozen). A cleared
 * party is only re-flagged by entries ADDED in a later import, so the same false positive does not come back every time.
 */
@Service
public class ScreeningService {

    public record AdHocResult(ScreeningOutcome outcome, double topScore, List<WatchlistIndex.Match> matches) {
    }

    private final ScreenedPartyRepository partyRepository;
    private final ScreeningResultRepository resultRepository;
    private final WatchlistVersionRepository versionRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final WatchlistIndex index;
    private final CaseService caseService;
    private final ComplianceActionPublisher actionPublisher;
    private final ComplianceProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ScreeningService(ScreenedPartyRepository partyRepository,
                            ScreeningResultRepository resultRepository,
                            WatchlistVersionRepository versionRepository,
                            ProcessedEventRepository processedEventRepository,
                            WatchlistIndex index,
                            CaseService caseService,
                            ComplianceActionPublisher actionPublisher,
                            ComplianceProperties properties,
                            ObjectMapper objectMapper,
                            MeterRegistry meterRegistry) {
        this.partyRepository = partyRepository;
        this.resultRepository = resultRepository;
        this.versionRepository = versionRepository;
        this.processedEventRepository = processedEventRepository;
        this.index = index;
        this.caseService = caseService;
        this.actionPublisher = actionPublisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void onAccountOpened(AccountOpenedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }
        screenParty(event.accountId(), event.ownerId(), event.ownerName(), ScreeningTrigger.ONBOARDING, null);
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    /**
     * Screens one party and applies the outcome. `onlyVersion`, when set, restricts a previously cleared party to entries
     * added/changed by that import.
     */
    @Transactional
    public ScreenedParty screenParty(UUID accountId, UUID ownerId, String name, ScreeningTrigger trigger, UUID onlyVersion) {
        ScreenedParty party = partyRepository.findByAccountId(accountId)
                .orElseGet(() -> partyRepository.save(ScreenedParty.of(accountId, ownerId, name)));

        boolean previouslyCleared = party.getStatus() == PartyStatus.CLEARED_FALSE_POSITIVE;
        Predicate<WatchlistIndex.Candidate> eligible = previouslyCleared && onlyVersion != null
                ? c -> onlyVersion.equals(c.versionId())
                : c -> true;

        List<WatchlistIndex.Match> matches = index.screen(name, properties.getSanctions().getReviewThreshold(), eligible);
        double top = matches.isEmpty() ? 0.0 : matches.get(0).score();
        ScreeningOutcome outcome = outcomeFor(top);
        BigDecimal topScore = BigDecimal.valueOf(top).setScale(4, RoundingMode.HALF_UP);

        UUID versionId = versionRepository.findFirstByOrderByImportedAtDesc().map(WatchlistVersion::getId).orElse(null);
        resultRepository.save(ScreeningResult.of(party.getId(), name, trigger, outcome, topScore, json(matches), versionId));
        party.recordScreening(topScore);
        meterRegistry.counter("neobank.sanctions.screenings", "outcome", outcome.name(), "trigger", trigger.name()).increment();

        boolean alreadyUnderReview = party.getStatus() == PartyStatus.POTENTIAL_MATCH || party.getStatus() == PartyStatus.CONFIRMED_MATCH;
        if (outcome != ScreeningOutcome.CLEAR && !alreadyUnderReview) {
            boolean freeze = outcome == ScreeningOutcome.STRONG_MATCH || properties.getSanctions().isFreezeOnPotentialMatch();
            party.flag(topScore, freeze);

            WatchlistIndex.Match best = matches.get(0);
            Severity severity = outcome == ScreeningOutcome.STRONG_MATCH ? Severity.CRITICAL : Severity.HIGH;
            String summary = String.format("Sanctions %s: '%s' matches %s '%s' (%s, score %.2f)",
                    outcome == ScreeningOutcome.STRONG_MATCH ? "STRONG MATCH" : "potential match",
                    name, best.listSource(), best.matchedName(), best.via().toLowerCase(), best.score());
            AmlCase amlCase = caseService.openOrAttach(CaseType.SANCTIONS_SCREENING, accountId, severity, summary);
            if (freeze) {
                caseService.requestFreeze(amlCase, null, "Sanctions " + outcome + " on screening (" + trigger + ")");
            }
            meterRegistry.counter("neobank.sanctions.hits", "outcome", outcome.name()).increment();
        }
        return partyRepository.save(party);
    }

    /** Re-screens every known party after a watchlist import; returns how many ended up flagged. */
    @Transactional
    public int rescreenAll(UUID newVersionId) {
        int flagged = 0;
        for (ScreenedParty party : partyRepository.findAll()) {
            PartyStatus before = party.getStatus();
            ScreenedParty after = screenParty(party.getAccountId(), party.getOwnerId(), party.getPartyName(),
                    ScreeningTrigger.WATCHLIST_UPDATE, newVersionId);
            if (before != after.getStatus() && after.getStatus() == PartyStatus.POTENTIAL_MATCH) {
                flagged++;
            }
        }
        return flagged;
    }

    /** Screens a name without storing anything - for an analyst checking a counterparty or applicant. */
    @Transactional(readOnly = true)
    public AdHocResult screenAdHoc(String name) {
        List<WatchlistIndex.Match> matches = index.screen(name, properties.getSanctions().getReviewThreshold(), c -> true);
        double top = matches.isEmpty() ? 0.0 : matches.get(0).score();
        return new AdHocResult(outcomeFor(top), top, matches);
    }

    /** Applies the decision on a closed sanctions case to the screened party (and unfreezes / freezes the account). */
    @EventListener
    @Transactional
    public void onCaseClosed(CaseClosedEvent event) {
        if (event.caseType() != CaseType.SANCTIONS_SCREENING) {
            return;
        }
        ScreenedParty party = partyRepository.findByAccountId(event.subjectAccountId()).orElse(null);
        if (party == null) {
            return;
        }
        switch (event.disposition()) {
            case FALSE_POSITIVE -> {
                boolean wasFrozenByUs = party.isAutoFrozen();
                party.clearAsFalsePositive(event.actor(), event.note());
                if (wasFrozenByUs) {
                    actionPublisher.unfreeze(party.getAccountId(), event.caseId(), "Sanctions hit cleared as false positive");
                    caseService.recordSystemEvent(event.caseId(), CaseEventType.ACCOUNT_UNFREEZE_REQUESTED, "Account unfreeze requested");
                }
                meterRegistry.counter("neobank.sanctions.cleared").increment();
            }
            case ACCOUNT_RESTRICTED -> {
                boolean alreadyFrozen = party.isAutoFrozen();
                party.confirmMatch(event.actor(), event.note(), true);
                if (!alreadyFrozen) {
                    actionPublisher.freeze(party.getAccountId(), event.caseId(), "Sanctions match confirmed");
                    caseService.recordSystemEvent(event.caseId(), CaseEventType.ACCOUNT_FREEZE_REQUESTED, "Account freeze requested");
                }
                meterRegistry.counter("neobank.sanctions.confirmed").increment();
            }
            default -> { }
        }
        partyRepository.save(party);
    }

    static ScreeningOutcome outcomeFor(double topScore) {
        if (topScore >= 1.0) {
            return ScreeningOutcome.STRONG_MATCH;
        }
        return topScore > 0.0 ? ScreeningOutcome.POTENTIAL_MATCH : ScreeningOutcome.CLEAR;
    }

    private String json(List<WatchlistIndex.Match> matches) {
        try {
            return objectMapper.writeValueAsString(matches);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise screening matches", e);
        }
    }
}
