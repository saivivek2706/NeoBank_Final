package com.neobank.compliance.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.compliance.domain.AmlAlert;
import com.neobank.compliance.domain.AmlCase;
import com.neobank.compliance.domain.CaseType;
import com.neobank.compliance.domain.Direction;
import com.neobank.compliance.domain.MonitoredTransaction;
import com.neobank.compliance.domain.ProcessedEvent;
import com.neobank.compliance.kafka.event.TransactionEvent;
import com.neobank.compliance.repository.AmlAlertRepository;
import com.neobank.compliance.repository.MonitoredTransactionRepository;
import com.neobank.compliance.repository.ProcessedEventRepository;
import com.neobank.compliance.rule.AlertCandidate;
import com.neobank.compliance.rule.AmlRule;
import com.neobank.compliance.rule.TransactionHistory;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Transaction monitoring: records each completed money movement, runs every AML rule against the account's recent history
 * and turns findings into alerts, which are grouped into one open case per account.
 *
 * Idempotent: an event id is processed once, and each alert carries a dedup key so re-evaluation never duplicates it.
 */
@Service
@Slf4j
public class MonitoringService {

    private final MonitoredTransactionRepository transactionRepository;
    private final AmlAlertRepository alertRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final CaseService caseService;
    private final List<AmlRule> rules;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public MonitoringService(MonitoredTransactionRepository transactionRepository,
                             AmlAlertRepository alertRepository,
                             ProcessedEventRepository processedEventRepository,
                             CaseService caseService,
                             List<AmlRule> rules,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.alertRepository = alertRepository;
        this.processedEventRepository = processedEventRepository;
        this.caseService = caseService;
        this.rules = rules;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void onTransaction(TransactionEvent event, Direction direction) {
        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }
        MonitoredTransaction tx = transactionRepository.save(MonitoredTransaction.of(event.eventId(), event.transactionId(),
                event.accountId(), direction, event.amount(), event.currency(),
                event.occurredAt() != null ? event.occurredAt() : Instant.now()));
        meterRegistry.counter("neobank.aml.transactions.monitored", "direction", direction.name()).increment();

        TransactionHistory history = (accountId, since) ->
                transactionRepository.findByAccountIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(accountId, since);

        for (AmlRule rule : rules) {
            Optional<AlertCandidate> finding = rule.evaluate(tx, history);
            if (finding.isEmpty()) {
                continue;
            }
            AlertCandidate c = finding.get();
            if (alertRepository.existsByDedupKey(c.dedupKey())) {
                continue;   // this rule already fired for this account in this window
            }
            AmlAlert alert = alertRepository.save(AmlAlert.of(c.ruleCode(), tx.getAccountId(), tx.getTransactionId(),
                    c.severity(), c.score(), c.summary(), json(c), c.dedupKey()));
            AmlCase amlCase = caseService.openOrAttach(CaseType.TRANSACTION_MONITORING, tx.getAccountId(), c.severity(),
                    c.ruleCode() + ": " + c.summary());
            caseService.attachAlert(alert, amlCase);
            meterRegistry.counter("neobank.aml.alerts", "rule", c.ruleCode(), "severity", c.severity().name()).increment();
            log.warn("AML alert {} on account {} -> case {}", c.ruleCode(), tx.getAccountId(), amlCase.getCaseNumber());
        }
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    private String json(AlertCandidate candidate) {
        try {
            return objectMapper.writeValueAsString(candidate.details());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise alert details", e);
        }
    }
}
