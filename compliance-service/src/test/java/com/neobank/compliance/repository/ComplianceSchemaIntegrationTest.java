package com.neobank.compliance.repository;

import com.neobank.compliance.domain.AmlAlert;
import com.neobank.compliance.domain.AmlCase;
import com.neobank.compliance.domain.CaseEvent;
import com.neobank.compliance.domain.CaseEventType;
import com.neobank.compliance.domain.CaseStatus;
import com.neobank.compliance.domain.CaseType;
import com.neobank.compliance.domain.Direction;
import com.neobank.compliance.domain.Disposition;
import com.neobank.compliance.domain.EntityType;
import com.neobank.compliance.domain.MonitoredTransaction;
import com.neobank.compliance.domain.PartyStatus;
import com.neobank.compliance.domain.ScreenedParty;
import com.neobank.compliance.domain.ScreeningOutcome;
import com.neobank.compliance.domain.ScreeningResult;
import com.neobank.compliance.domain.ScreeningTrigger;
import com.neobank.compliance.domain.Severity;
import com.neobank.compliance.domain.WatchlistAlias;
import com.neobank.compliance.domain.WatchlistEntry;
import com.neobank.compliance.domain.WatchlistMode;
import com.neobank.compliance.domain.WatchlistVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compliance schema against a REAL Postgres with the production Flyway migration (and Hibernate's ddl-auto=validate, so
 * every entity mapping is checked against the actual columns). Proves the guarantees that live in the database: one open case
 * per account and type, append-only case history and screening evidence, alert de-duplication.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ComplianceSchemaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("neobank_compliance_test")
            .withUsername("neobank")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private AmlCaseRepository caseRepository;
    @Autowired private AmlAlertRepository alertRepository;
    @Autowired private CaseEventRepository caseEventRepository;
    @Autowired private MonitoredTransactionRepository transactionRepository;
    @Autowired private ScreenedPartyRepository partyRepository;
    @Autowired private ScreeningResultRepository resultRepository;
    @Autowired private WatchlistVersionRepository versionRepository;
    @Autowired private WatchlistEntryRepository entryRepository;
    @Autowired private WatchlistAliasRepository aliasRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final Instant now = Instant.now();

    private AmlCase newCase(UUID account, CaseType type) {
        return caseRepository.saveAndFlush(AmlCase.open("AML-T-" + UUID.randomUUID().toString().substring(0, 8), account, type,
                Severity.MEDIUM, "summary", now, now.plus(Duration.ofDays(7))));
    }

    @Test
    void theMigrationCreatesEveryTable_andAllEntityMappingsValidateAgainstIt() {
        Integer tables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables WHERE table_name IN
                ('watchlist_versions', 'watchlist_entries', 'watchlist_aliases', 'screened_parties', 'screening_results',
                 'monitored_transactions', 'aml_cases', 'aml_alerts', 'case_events', 'outbox_events', 'processed_events', 'shedlock')""",
                Integer.class);

        assertThat(tables).isEqualTo(12);   // (context start-up already ran Hibernate's schema validation)
    }

    // ---------------------------------------------------------------- one open case per subject

    @Test
    void thereCanOnlyBeOneOpenCasePerAccountAndType() {
        UUID account = UUID.randomUUID();
        newCase(account, CaseType.TRANSACTION_MONITORING);

        assertThatThrownBy(() -> newCase(account, CaseType.TRANSACTION_MONITORING))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDifferentTypeOrAccountMayHaveItsOwnOpenCase_andAClosedCaseFreesTheSlot() {
        UUID account = UUID.randomUUID();
        AmlCase monitoring = newCase(account, CaseType.TRANSACTION_MONITORING);
        newCase(account, CaseType.SANCTIONS_SCREENING);
        newCase(UUID.randomUUID(), CaseType.TRANSACTION_MONITORING);

        monitoring.close(UUID.randomUUID(), Disposition.NO_SUSPICION, now);
        caseRepository.saveAndFlush(monitoring);
        AmlCase next = newCase(account, CaseType.TRANSACTION_MONITORING);   // allowed again: the previous one is closed

        assertThat(caseRepository.findOpen(account, CaseType.TRANSACTION_MONITORING)).get().extracting(AmlCase::getId).isEqualTo(next.getId());
    }

    @Test
    void caseNumbersComeFromADatabaseSequence() {
        long first = caseRepository.nextCaseSequence();
        long second = caseRepository.nextCaseSequence();

        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    void openAndOverdueCountsAndFiltersWork() {
        UUID reviewer = UUID.randomUUID();
        AmlCase overdue = caseRepository.saveAndFlush(AmlCase.open("AML-T-" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
                CaseType.TRANSACTION_MONITORING, Severity.CRITICAL, "late", now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(1))));
        AmlCase fresh = newCase(UUID.randomUUID(), CaseType.SANCTIONS_SCREENING);
        overdue.assignTo(reviewer);
        caseRepository.saveAndFlush(overdue);

        assertThat(caseRepository.countOpen()).isEqualTo(2);
        assertThat(caseRepository.countOverdue(now)).isEqualTo(1);
        assertThat(caseRepository.countOpenByPriority(Severity.CRITICAL)).isEqualTo(1);
        assertThat(caseRepository.findOpenAssignedTo(reviewer, PageRequest.of(0, 10)).getContent()).extracting(AmlCase::getId).containsExactly(overdue.getId());
        assertThat(caseRepository.findByStatus(CaseStatus.IN_REVIEW, PageRequest.of(0, 10)).getContent()).hasSize(1);
        assertThat(caseRepository.findAllOpen(PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(fresh.getVersion()).isZero();
    }

    @Test
    void concurrentEditsToACaseAreDetectedByOptimisticLocking() {
        AmlCase c = newCase(UUID.randomUUID(), CaseType.TRANSACTION_MONITORING);
        jdbcTemplate.update("UPDATE aml_cases SET version = version + 1 WHERE id = ?", c.getId());   // someone else saved first
        c.assignTo(UUID.randomUUID());

        assertThatThrownBy(() -> caseRepository.saveAndFlush(c)).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    }

    // ---------------------------------------------------------------- append-only evidence

    @Test
    void caseHistoryCannotBeAlteredOrDeleted() {
        AmlCase c = newCase(UUID.randomUUID(), CaseType.TRANSACTION_MONITORING);
        caseEventRepository.saveAndFlush(CaseEvent.of(c.getId(), CaseEventType.NOTE, UUID.randomUUID(), CaseStatus.OPEN, CaseStatus.OPEN, "original"));

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE case_events SET note = 'rewritten history'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void caseHistoryCannotBeDeleted() {
        AmlCase c = newCase(UUID.randomUUID(), CaseType.TRANSACTION_MONITORING);
        caseEventRepository.saveAndFlush(CaseEvent.of(c.getId(), CaseEventType.OPENED, null, null, CaseStatus.OPEN, "opened"));

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM case_events"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void screeningEvidenceCannotBeAltered() {
        ScreenedParty party = partyRepository.saveAndFlush(ScreenedParty.of(UUID.randomUUID(), UUID.randomUUID(), "Ivan Petrov"));
        resultRepository.saveAndFlush(ScreeningResult.of(party.getId(), "Ivan Petrov", ScreeningTrigger.ONBOARDING,
                ScreeningOutcome.STRONG_MATCH, new BigDecimal("1.0000"), "[{\"score\":1.0}]", null));

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE screening_results SET outcome = 'CLEAR'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void caseHistoryIsReadBackInOrder() {
        AmlCase c = newCase(UUID.randomUUID(), CaseType.TRANSACTION_MONITORING);
        caseEventRepository.saveAndFlush(CaseEvent.of(c.getId(), CaseEventType.OPENED, null, null, CaseStatus.OPEN, "opened"));
        caseEventRepository.saveAndFlush(CaseEvent.of(c.getId(), CaseEventType.ASSIGNED, UUID.randomUUID(), CaseStatus.OPEN, CaseStatus.IN_REVIEW, "assigned"));

        assertThat(caseEventRepository.findByCaseIdOrderByCreatedAtAsc(c.getId())).extracting(CaseEvent::getEventType)
                .containsExactly(CaseEventType.OPENED, CaseEventType.ASSIGNED);
    }

    // ---------------------------------------------------------------- alerts and monitored transactions

    @Test
    void anAlertWithTheSameDedupKeyCannotBeStoredTwice() {
        UUID account = UUID.randomUUID();
        alertRepository.saveAndFlush(AmlAlert.of("STRUCTURING", account, null, Severity.HIGH, 80, "s", "{}", "STRUCTURING:acc:OUT:2026-03-10"));
        assertThat(alertRepository.existsByDedupKey("STRUCTURING:acc:OUT:2026-03-10")).isTrue();

        assertThatThrownBy(() -> alertRepository.saveAndFlush(
                AmlAlert.of("STRUCTURING", account, null, Severity.HIGH, 80, "s", "{}", "STRUCTURING:acc:OUT:2026-03-10")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void alertsAreGroupedUnderTheirCase() {
        AmlCase c = newCase(UUID.randomUUID(), CaseType.TRANSACTION_MONITORING);
        AmlAlert a = AmlAlert.of("LARGE_TRANSACTION", c.getSubjectAccountId(), UUID.randomUUID(), Severity.MEDIUM, 60, "s", "{}", "k1");
        a.attachTo(c.getId());
        alertRepository.saveAndFlush(a);

        assertThat(alertRepository.findByCaseIdOrderByCreatedAtAsc(c.getId())).hasSize(1);
        assertThat(alertRepository.countByCreatedAtAfter(now.minus(Duration.ofMinutes(5)))).isEqualTo(1);
    }

    @Test
    void aMonitoredEventIsStoredOnce_andTheHistoryWindowQueryIsOrderedAndBounded() {
        UUID account = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        transactionRepository.saveAndFlush(MonitoredTransaction.of(UUID.randomUUID(), UUID.randomUUID(), account, Direction.OUT, new BigDecimal("10"), "SGD", now.minus(Duration.ofHours(30))));
        transactionRepository.saveAndFlush(MonitoredTransaction.of(eventId, UUID.randomUUID(), account, Direction.OUT, new BigDecimal("20"), "SGD", now.minus(Duration.ofHours(2))));
        transactionRepository.saveAndFlush(MonitoredTransaction.of(UUID.randomUUID(), UUID.randomUUID(), account, Direction.IN, new BigDecimal("30"), "SGD", now.minus(Duration.ofHours(1))));
        transactionRepository.saveAndFlush(MonitoredTransaction.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Direction.OUT, new BigDecimal("40"), "SGD", now));

        List<MonitoredTransaction> window = transactionRepository
                .findByAccountIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(account, now.minus(Duration.ofHours(24)));

        assertThat(window).extracting(MonitoredTransaction::getAmount).extracting(BigDecimal::intValue).containsExactly(20, 30);   // 24h window, this account only, oldest first
        assertThatThrownBy(() -> transactionRepository.saveAndFlush(MonitoredTransaction.of(eventId, UUID.randomUUID(), account, Direction.OUT, BigDecimal.ONE, "SGD", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- watchlists and parties

    @Test
    void watchlistEntriesAreUniquePerSourceAndExternalId_andAliasesAreStoredWithThem() {
        WatchlistVersion version = versionRepository.saveAndFlush(WatchlistVersion.of(UUID.randomUUID(), WatchlistMode.FULL, 1, 1, 0, 0, "a".repeat(64)));
        WatchlistEntry entry = entryRepository.saveAndFlush(WatchlistEntry.of("OFAC_SDN", "1", "Ivan Petrov", "ivan petrov", "RU", EntityType.INDIVIDUAL, version.getId()));
        aliasRepository.saveAndFlush(WatchlistAlias.of(entry.getId(), "Ivan Petroff", "ivan petroff"));

        assertThat(aliasRepository.findByEntryIdIn(List.of(entry.getId()))).hasSize(1);
        assertThat(entryRepository.findByActiveTrue()).extracting(WatchlistEntry::getId).containsExactly(entry.getId());
        assertThat(entryRepository.findByNormalizedNameContaining("petr", PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        aliasRepository.deleteByEntryId(entry.getId());
        aliasRepository.flush();
        assertThat(aliasRepository.findByEntryId(entry.getId())).isEmpty();

        assertThatThrownBy(() -> entryRepository.saveAndFlush(WatchlistEntry.of("OFAC_SDN", "1", "Someone Else", "someone else", null, EntityType.INDIVIDUAL, version.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAccountHasAtMostOneScreenedParty() {
        UUID account = UUID.randomUUID();
        partyRepository.saveAndFlush(ScreenedParty.of(account, UUID.randomUUID(), "Jane Doe"));

        assertThatThrownBy(() -> partyRepository.saveAndFlush(ScreenedParty.of(account, UUID.randomUUID(), "Jane Doe")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void partiesAreFilteredByStatus() {
        ScreenedParty hit = ScreenedParty.of(UUID.randomUUID(), UUID.randomUUID(), "Ivan Petrov");
        hit.flag(new BigDecimal("1.0000"), true);
        partyRepository.saveAndFlush(hit);
        partyRepository.saveAndFlush(ScreenedParty.of(UUID.randomUUID(), UUID.randomUUID(), "Jane Doe"));

        assertThat(partyRepository.findByStatus(PartyStatus.POTENTIAL_MATCH, PageRequest.of(0, 10)).getContent()).hasSize(1);
        assertThat(partyRepository.countByStatus(PartyStatus.CLEAR)).isEqualTo(1);
        assertThat(partyRepository.findByAccountId(hit.getAccountId())).get().extracting(ScreenedParty::isAutoFrozen).isEqualTo(true);
    }
}
