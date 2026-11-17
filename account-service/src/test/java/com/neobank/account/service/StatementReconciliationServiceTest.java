package com.neobank.account.service;

import com.neobank.account.domain.BreakType;
import com.neobank.account.domain.ExternalStatement;
import com.neobank.account.domain.MovementDirection;
import com.neobank.account.domain.PostingDirection;
import com.neobank.account.domain.ReconciliationBreak;
import com.neobank.account.domain.ReconciliationRun;
import com.neobank.account.repository.ExternalStatementLineRepository;
import com.neobank.account.repository.ExternalStatementRepository;
import com.neobank.account.repository.PostingRepository;
import com.neobank.account.repository.ReconciliationBreakRepository;
import com.neobank.account.repository.ReconciliationRunRepository;
import com.neobank.account.service.StatementReconciliationService.LineInput;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementReconciliationServiceTest {

    private static final LocalDate DAY = LocalDate.parse("2026-03-01");

    @Mock private ExternalStatementRepository statementRepository;
    @Mock private ExternalStatementLineRepository lineRepository;
    @Mock private PostingRepository postingRepository;
    @Mock private ReconciliationRunRepository runRepository;
    @Mock private ReconciliationBreakRepository breakRepository;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private StatementReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new StatementReconciliationService(statementRepository, lineRepository, postingRepository,
                runRepository, breakRepository, meters);
        lenient().when(statementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static LineInput line(String ref, MovementDirection dir, String amount) {
        return new LineInput(ref, dir, new BigDecimal(amount), "SGD", null);
    }

    /** What the ledger query returns: [externalReference, journalId, direction, amount, currency]. */
    private static Object[] ledgerRow(String ref, PostingDirection dir, String amount) {
        return new Object[]{ref, UUID.randomUUID(), dir, new BigDecimal(amount), "SGD"};
    }

    private void ledgerHas(Object[]... rows) {
        when(postingRepository.externalMovements(eq("EXTERNAL_FUNDING"), eq("SGD"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(rows));
    }

    @Test
    void aCleanDayProducesACleanRun() {
        ledgerHas(ledgerRow("R1", PostingDirection.DEBIT, "500.00"), ledgerRow("R2", PostingDirection.CREDIT, "40.00"));

        var report = service.reconcile("SETTLEMENT_BANK", DAY, "SGD",
                List.of(line("R1", MovementDirection.INFLOW, "500.00"), line("R2", MovementDirection.OUTFLOW, "40.00")), UUID.randomUUID());

        assertThat(report.run().getStatus()).isEqualTo("CLEAN");
        assertThat(report.run().getMatched()).isEqualTo(2);
        assertThat(report.breaks()).isEmpty();
    }

    @Test
    void aLedgerDebitToTheCashAccountIsMoneyReceived_aCreditIsMoneyPaidOut() {
        // If the direction mapping were inverted every line would show up as a DIRECTION_MISMATCH.
        ledgerHas(ledgerRow("IN", PostingDirection.DEBIT, "10.00"), ledgerRow("OUT", PostingDirection.CREDIT, "10.00"));

        var report = service.reconcile("SETTLEMENT_BANK", DAY, "SGD",
                List.of(line("IN", MovementDirection.INFLOW, "10.00"), line("OUT", MovementDirection.OUTFLOW, "10.00")), UUID.randomUUID());

        assertThat(report.breaks()).isEmpty();
    }

    @Test
    void differencesBecomeOpenBreaks_andAreCounted() {
        ledgerHas(ledgerRow("R1", PostingDirection.DEBIT, "500.00"), ledgerRow("ONLYBOOK", PostingDirection.DEBIT, "9.00"));

        var report = service.reconcile("SETTLEMENT_BANK", DAY, "SGD",
                List.of(line("R1", MovementDirection.INFLOW, "499.00"), line("ONLYBANK", MovementDirection.INFLOW, "7.00")), UUID.randomUUID());

        assertThat(report.run().getStatus()).isEqualTo("BREAKS");
        assertThat(report.run().getBreakCount()).isEqualTo(3);
        assertThat(report.breaks()).extracting(ReconciliationBreak::getBreakType)
                .containsExactlyInAnyOrder(BreakType.AMOUNT_MISMATCH, BreakType.MISSING_IN_LEDGER, BreakType.MISSING_IN_STATEMENT);
        assertThat(report.breaks()).allMatch(b -> !b.isResolved());
        verify(breakRepository).saveAll(any());
        assertThat(meters.get("neobank.reconciliation.breaks").tag("currency", "SGD").counter().count()).isEqualTo(3.0);
    }

    @Test
    void aLedgerMovementWithoutAnExternalReferenceIsMatchedByItsJournalId() {
        UUID journalId = UUID.randomUUID();
        ledgerHas(new Object[]{null, journalId, PostingDirection.DEBIT, new BigDecimal("5.00"), "SGD"});

        var report = service.reconcile("SETTLEMENT_BANK", DAY, "SGD",
                List.of(line("NB-" + journalId, MovementDirection.INFLOW, "5.00")), UUID.randomUUID());

        assertThat(report.breaks()).isEmpty();
    }

    @Test
    void theSameStatementCannotBeUploadedTwice() {
        when(statementRepository.existsBySourceAndStatementDateAndCurrency("SETTLEMENT_BANK", DAY, "SGD")).thenReturn(true);

        assertThatThrownBy(() -> service.reconcile("SETTLEMENT_BANK", DAY, "SGD", List.of(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already uploaded");
        verify(statementRepository, never()).save(any(ExternalStatement.class));
    }

    @Test
    void validatesTheUpload() {
        UUID by = UUID.randomUUID();
        assertThatThrownBy(() -> service.reconcile(" ", DAY, "SGD", List.of(), by)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcile("S", null, "SGD", List.of(), by)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcile("S", DAY, "sgd", List.of(), by)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcile("S", DAY, "SGD",
                List.of(new LineInput("R", MovementDirection.INFLOW, BigDecimal.ONE, "USD", null)), by))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("USD");
    }

    @Test
    void resolvingABreakRequiresANote_andOnlyWorksOnce() {
        ReconciliationBreak open = ReconciliationBreak.of(UUID.randomUUID(), BreakType.AMOUNT_MISMATCH, "R1",
                BigDecimal.TEN, BigDecimal.ONE, "detail");
        UUID breakId = UUID.randomUUID();
        UUID operator = UUID.randomUUID();
        when(breakRepository.findById(breakId)).thenReturn(Optional.of(open));
        when(breakRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.resolveBreak(breakId, operator, " ")).isInstanceOf(IllegalArgumentException.class);

        ReconciliationBreak resolved = service.resolveBreak(breakId, operator, "  Bank fee netted; confirmed by ops  ");
        assertThat(resolved.isResolved()).isTrue();
        assertThat(resolved.getResolvedBy()).isEqualTo(operator);
        assertThat(resolved.getResolutionNote()).isEqualTo("Bank fee netted; confirmed by ops");
        assertThat(resolved.getResolvedAt()).isNotNull();

        assertThatThrownBy(() -> service.resolveBreak(breakId, operator, "again"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already resolved");
    }

    @Test
    void unknownRunAndBreakAreNotFound() {
        when(runRepository.findById(any())).thenReturn(Optional.empty());
        when(breakRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRun(UUID.randomUUID())).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.resolveBreak(UUID.randomUUID(), UUID.randomUUID(), "n"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getRunReturnsTheRunWithItsBreaks() {
        ReconciliationRun run = ReconciliationRun.of(UUID.randomUUID(), 1, 1, 0, 1);
        UUID runId = UUID.randomUUID();
        ReconciliationBreak b = ReconciliationBreak.of(runId, BreakType.MISSING_IN_LEDGER, "R", BigDecimal.ONE, null, "d");
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(breakRepository.findByRunId(runId)).thenReturn(List.of(b));

        var report = service.getRun(runId);

        assertThat(report.run()).isSameAs(run);
        assertThat(report.breaks()).containsExactly(b);
    }

    @Test
    void theStatementWindowIsTheUtcDay() {
        ledgerHas();
        service.reconcile("SETTLEMENT_BANK", DAY, "SGD", List.of(), UUID.randomUUID());

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(postingRepository).externalMovements(eq("EXTERNAL_FUNDING"), eq("SGD"), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(to.getValue()).isEqualTo(Instant.parse("2026-03-02T00:00:00Z"));
    }
}
