package com.neobank.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.compliance.config.ComplianceProperties;
import com.neobank.compliance.domain.AmlAlert;
import com.neobank.compliance.domain.AmlCase;
import com.neobank.compliance.domain.CaseType;
import com.neobank.compliance.domain.Direction;
import com.neobank.compliance.domain.MonitoredTransaction;
import com.neobank.compliance.domain.Severity;
import com.neobank.compliance.kafka.event.TransactionEvent;
import com.neobank.compliance.repository.AmlAlertRepository;
import com.neobank.compliance.repository.MonitoredTransactionRepository;
import com.neobank.compliance.repository.ProcessedEventRepository;
import com.neobank.compliance.rule.AmlRule;
import com.neobank.compliance.rule.HighVelocityRule;
import com.neobank.compliance.rule.LargeTransactionRule;
import com.neobank.compliance.rule.RapidMovementRule;
import com.neobank.compliance.rule.StructuringRule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitoringServiceTest {

    @Mock private MonitoredTransactionRepository transactionRepository;
    @Mock private AmlAlertRepository alertRepository;
    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private CaseService caseService;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UUID account = UUID.randomUUID();
    private final List<MonitoredTransaction> stored = new ArrayList<>();
    private MonitoringService service;

    @BeforeEach
    void setUp() {
        ComplianceProperties props = new ComplianceProperties();
        List<AmlRule> rules = List.of(new LargeTransactionRule(props), new StructuringRule(props),
                new RapidMovementRule(props), new HighVelocityRule(props));
        service = new MonitoringService(transactionRepository, alertRepository, processedEventRepository, caseService, rules,
                new ObjectMapper().findAndRegisterModules(), meters);
        lenient().when(transactionRepository.save(any())).thenAnswer(inv -> {
            MonitoredTransaction t = inv.getArgument(0);
            stored.add(t);
            return t;
        });
        lenient().when(transactionRepository.findByAccountIdAndOccurredAtGreaterThanEqualOrderByOccurredAtAsc(any(), any()))
                .thenAnswer(inv -> stored.stream().filter(t -> !t.getOccurredAt().isBefore(inv.getArgument(1))).toList());
        lenient().when(alertRepository.existsByDedupKey(anyString())).thenReturn(false);
        lenient().when(alertRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(caseService.openOrAttach(any(), any(), any(), anyString())).thenAnswer(inv ->
                AmlCase.open("AML-2026-000001", account, inv.getArgument(0), inv.getArgument(2), "s", Instant.now(), Instant.now()));
    }

    private TransactionEvent event(String amount) {
        return new TransactionEvent(UUID.randomUUID(), UUID.randomUUID(), account, new BigDecimal(amount), "SGD", null, Instant.now());
    }

    @Test
    void anOrdinaryTransactionIsRecordedButRaisesNothing() {
        TransactionEvent e = event("100.00");

        service.onTransaction(e, Direction.OUT);

        assertThat(stored).singleElement().satisfies(t -> {
            assertThat(t.getAccountId()).isEqualTo(account);
            assertThat(t.getDirection()).isEqualTo(Direction.OUT);
        });
        verifyNoInteractions(caseService);
        verify(alertRepository, never()).save(any());
        verify(processedEventRepository).save(any());
        assertThat(meters.get("neobank.aml.transactions.monitored").tag("direction", "OUT").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aLargeTransferRaisesAnAlert_andOpensOrAttachesToTheAccountsCase() {
        service.onTransaction(event("25000.00"), Direction.OUT);

        ArgumentCaptor<AmlAlert> alert = ArgumentCaptor.forClass(AmlAlert.class);
        verify(alertRepository).save(alert.capture());
        assertThat(alert.getValue().getRuleCode()).isEqualTo("LARGE_TRANSACTION");
        assertThat(alert.getValue().getAccountId()).isEqualTo(account);
        assertThat(alert.getValue().getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(alert.getValue().getDetails()).contains("25000.00").contains("threshold");

        verify(caseService).openOrAttach(eq(CaseType.TRANSACTION_MONITORING), eq(account), eq(Severity.MEDIUM), org.mockito.ArgumentMatchers.startsWith("LARGE_TRANSACTION"));
        verify(caseService).attachAlert(any(AmlAlert.class), any(AmlCase.class));
        assertThat(meters.get("neobank.aml.alerts").tag("rule", "LARGE_TRANSACTION").counter().count()).isEqualTo(1.0);
    }

    @Test
    void anAlreadyRaisedAlertIsNotDuplicated() {
        when(alertRepository.existsByDedupKey(anyString())).thenReturn(true);

        service.onTransaction(event("25000.00"), Direction.OUT);

        verify(alertRepository, never()).save(any());
        verifyNoInteractions(caseService);
    }

    @Test
    void aRedeliveredEventIsProcessedOnce() {
        TransactionEvent e = event("25000.00");
        when(processedEventRepository.existsById(e.eventId())).thenReturn(true);

        service.onTransaction(e, Direction.OUT);

        verifyNoInteractions(transactionRepository, alertRepository, caseService);
    }

    @Test
    void severalRulesFeedTheAccountsCase() {
        // received 20,000 earlier, now sends 19,000 out: LARGE_TRANSACTION (>= 10,000) and RAPID_MOVEMENT both fire
        service.onTransaction(event("20000.00"), Direction.IN);
        service.onTransaction(event("19000.00"), Direction.OUT);

        ArgumentCaptor<AmlAlert> alerts = ArgumentCaptor.forClass(AmlAlert.class);
        verify(alertRepository, times(3)).save(alerts.capture());   // IN: LARGE;  OUT: LARGE + RAPID_MOVEMENT
        assertThat(alerts.getAllValues()).extracting(AmlAlert::getRuleCode)
                .containsExactly("LARGE_TRANSACTION", "LARGE_TRANSACTION", "RAPID_MOVEMENT");
        verify(caseService, times(3)).openOrAttach(eq(CaseType.TRANSACTION_MONITORING), eq(account), any(), anyString());
    }

    @Test
    void structuringIsDetectedAcrossSeparateEvents() {
        service.onTransaction(event("9000"), Direction.OUT);
        service.onTransaction(event("9500"), Direction.OUT);
        verify(alertRepository, never()).save(any());

        service.onTransaction(event("9900"), Direction.OUT);   // third one just under 10,000 within the window

        ArgumentCaptor<AmlAlert> alert = ArgumentCaptor.forClass(AmlAlert.class);
        verify(alertRepository).save(alert.capture());
        assertThat(alert.getValue().getRuleCode()).isEqualTo("STRUCTURING");
        assertThat(alert.getValue().getSeverity()).isEqualTo(Severity.HIGH);
    }
}
