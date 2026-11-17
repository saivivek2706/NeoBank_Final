package com.neobank.account.service;

import com.neobank.account.domain.ExternalStatement;
import com.neobank.account.domain.ExternalStatementLine;
import com.neobank.account.domain.MovementDirection;
import com.neobank.account.domain.PostingDirection;
import com.neobank.account.domain.ReconciliationBreak;
import com.neobank.account.domain.ReconciliationRun;
import com.neobank.account.domain.SystemAccount;
import com.neobank.account.repository.ExternalStatementLineRepository;
import com.neobank.account.repository.ExternalStatementRepository;
import com.neobank.account.repository.PostingRepository;
import com.neobank.account.repository.ReconciliationBreakRepository;
import com.neobank.account.repository.ReconciliationRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Reconciles the bank's books against the settlement bank's own statement for one date and currency.
 *
 * The ledger side is every posting against EXTERNAL_FUNDING (cash at the settlement bank) on that UTC day: a DEBIT
 * is money received (INFLOW), a CREDIT is money paid out (OUTFLOW). Each difference becomes a break that stays open
 * until an operator resolves it with a note; the open-break gauge feeds an alert.
 */
@Service
public class StatementReconciliationService {

    /** One parsed statement line, before it is persisted. */
    public record LineInput(String reference, MovementDirection direction, BigDecimal amount, String currency,
                            String description) {
    }

    public record RunReport(ReconciliationRun run, List<ReconciliationBreak> breaks) {
    }

    private final ExternalStatementRepository statementRepository;
    private final ExternalStatementLineRepository lineRepository;
    private final PostingRepository postingRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationBreakRepository breakRepository;
    private final MeterRegistry meterRegistry;

    public StatementReconciliationService(ExternalStatementRepository statementRepository,
                                          ExternalStatementLineRepository lineRepository,
                                          PostingRepository postingRepository,
                                          ReconciliationRunRepository runRepository,
                                          ReconciliationBreakRepository breakRepository,
                                          MeterRegistry meterRegistry) {
        this.statementRepository = statementRepository;
        this.lineRepository = lineRepository;
        this.postingRepository = postingRepository;
        this.runRepository = runRepository;
        this.breakRepository = breakRepository;
        this.meterRegistry = meterRegistry;
        Gauge.builder("neobank.reconciliation.open.breaks", breakRepository, ReconciliationBreakRepository::countByResolvedFalse)
                .description("Unresolved differences between the ledger and external bank statements").register(meterRegistry);
    }

    @Transactional
    public RunReport reconcile(String source, LocalDate statementDate, String currency, List<LineInput> lines, UUID uploadedBy) {
        if (source == null || source.isBlank() || source.length() > 50) {
            throw new IllegalArgumentException("source is required (max 50 characters)");
        }
        if (statementDate == null) {
            throw new IllegalArgumentException("statementDate is required");
        }
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code");
        }
        for (LineInput line : lines) {
            if (!currency.equals(line.currency())) {
                throw new IllegalArgumentException("Line " + line.reference() + " is in " + line.currency()
                        + " but the statement is in " + currency);
            }
            if (line.reference() == null || line.reference().isBlank() || line.reference().length() > 100) {
                throw new IllegalArgumentException("Every statement line needs a reference (max 100 characters)");
            }
            if (line.amount() == null || line.amount().signum() <= 0) {
                throw new IllegalArgumentException("Line " + line.reference() + ": amount must be positive");
            }
        }
        if (statementRepository.existsBySourceAndStatementDateAndCurrency(source, statementDate, currency)) {
            throw new IllegalStateException("A " + source + " statement for " + statementDate + " " + currency
                    + " was already uploaded");
        }

        ExternalStatement statement = statementRepository.save(
                ExternalStatement.of(source, statementDate, currency, uploadedBy, lines.size()));
        List<ExternalStatementLine> stored = new ArrayList<>();
        for (LineInput line : lines) {
            stored.add(ExternalStatementLine.of(statement.getId(), line.reference().trim(), line.direction(),
                    line.amount(), line.currency(), line.description()));
        }
        lineRepository.saveAll(stored);

        List<StatementMatcher.Entry> bankEntries = stored.stream()
                .map(l -> new StatementMatcher.Entry(l.getReference(), l.getDirection(), l.getAmount(), l.getCurrency()))
                .toList();
        List<StatementMatcher.Entry> ledgerEntries = ledgerMovements(statementDate, currency);

        StatementMatcher.Result result = StatementMatcher.match(bankEntries, ledgerEntries);

        ReconciliationRun run = runRepository.save(ReconciliationRun.of(statement.getId(), bankEntries.size(),
                ledgerEntries.size(), result.matched(), result.differences().size()));
        List<ReconciliationBreak> breaks = new ArrayList<>();
        for (StatementMatcher.Difference d : result.differences()) {
            breaks.add(ReconciliationBreak.of(run.getId(), d.type(), d.reference(), d.statementAmount(),
                    d.ledgerAmount(), d.detail()));
        }
        breakRepository.saveAll(breaks);
        if (!breaks.isEmpty()) {
            meterRegistry.counter("neobank.reconciliation.breaks", "currency", currency).increment(breaks.size());
        }
        meterRegistry.counter("neobank.reconciliation.runs", "status", run.getStatus()).increment();
        return new RunReport(run, breaks);
    }

    @Transactional(readOnly = true)
    public RunReport getRun(UUID runId) {
        ReconciliationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new NoSuchElementException("Reconciliation run " + runId + " not found"));
        return new RunReport(run, breakRepository.findByRunId(runId));
    }

    @Transactional(readOnly = true)
    public List<ReconciliationRun> recentRuns() {
        return runRepository.findTop50ByOrderByRunAtDesc();
    }

    @Transactional
    public ReconciliationBreak resolveBreak(UUID breakId, UUID resolvedBy, String note) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A resolution note is required");
        }
        ReconciliationBreak b = breakRepository.findById(breakId)
                .orElseThrow(() -> new NoSuchElementException("Break " + breakId + " not found"));
        b.resolve(resolvedBy, note.trim());
        return breakRepository.save(b);
    }

    private List<StatementMatcher.Entry> ledgerMovements(LocalDate date, String currency) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<StatementMatcher.Entry> entries = new ArrayList<>();
        for (Object[] row : postingRepository.externalMovements(SystemAccount.EXTERNAL_FUNDING.name(), currency, from, to)) {
            String reference = row[0] != null ? (String) row[0] : "NB-" + row[1];
            PostingDirection direction = (PostingDirection) row[2];
            // EXTERNAL_FUNDING is the bank's cash (an asset): a DEBIT increases it = money received.
            MovementDirection movement = direction == PostingDirection.DEBIT ? MovementDirection.INFLOW : MovementDirection.OUTFLOW;
            entries.add(new StatementMatcher.Entry(reference, movement, (BigDecimal) row[3], (String) row[4]));
        }
        return entries;
    }
}
