package com.neobank.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.transaction.domain.OutboxEvent;
import com.neobank.transaction.domain.Transaction;
import com.neobank.transaction.domain.TransactionStatus;
import com.neobank.transaction.client.AccountClient;
import com.neobank.transaction.dto.TransferRequest;
import com.neobank.transaction.exception.IdempotencyKeyReuseException;
import com.neobank.transaction.exception.StepUpRequiredException;
import com.neobank.transaction.exception.VelocityLimitExceededException;
import com.neobank.transaction.kafka.KafkaTopics;
import com.neobank.transaction.kafka.event.TransactionEvent;
import com.neobank.transaction.metrics.SagaMetrics;
import com.neobank.transaction.repository.OutboxEventRepository;
import com.neobank.transaction.repository.ProcessedEventRepository;
import com.neobank.transaction.repository.TransactionRepository;
import com.neobank.transaction.websocket.TransactionSocketNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private TransactionSocketNotifier socketNotifier;
    @Mock private AccountClient accountClient;
    @Mock private VelocityLimitService velocityLimitService;
    @Mock private StepUpTokenValidator stepUpTokenValidator;

    private TransferService transferService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        transferService = new TransferService(transactionRepository, outboxEventRepository,
                processedEventRepository, new ObjectMapper().findAndRegisterModules(), socketNotifier,
                accountClient, velocityLimitService, stepUpTokenValidator,
                new SagaMetrics(meterRegistry), new BigDecimal("1000.00"));

        // Mockito does not run JPA's UUID generation when saveAndFlush is mocked.
        lenient().doAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
            return tx;
        }).when(transactionRepository).saveAndFlush(any(Transaction.class));
    }

    private Transaction transactionWithId(UUID from, UUID to, BigDecimal amount, String currency) {
        Transaction tx = Transaction.initiate(from, to, amount, currency);
        ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        return tx;
    }

    @Test
    void initiateTransfer_rejectsSelfTransfer() {
        UUID accountId = UUID.randomUUID();
        TransferRequest request = new TransferRequest(accountId, accountId, BigDecimal.TEN, "SGD", null);

        assertThatThrownBy(() -> transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer test-token", "key-0001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same account");

        verifyNoInteractions(transactionRepository);
    }

    @Test
    void initiateTransfer_createsTransaction_andPublishesDebitRequested() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        TransferRequest request = new TransferRequest(from, to, new BigDecimal("100.00"), "SGD", null);

        Transaction tx = transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer test-token", "key-0001").transaction();

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.DEBIT_PENDING);
        assertThat(tx.getFromAccountId()).isEqualTo(from);
        assertThat(tx.getToAccountId()).isEqualTo(to);

        verify(transactionRepository).saveAndFlush(tx);
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals(KafkaTopics.DEBIT_REQUESTED)));
        verify(socketNotifier).notifyStatus(tx);
    }

    @Test
    void initiateTransfer_rejectsWhenCallerDoesNotOwnSourceAccount() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        TransferRequest request = new TransferRequest(from, to, new BigDecimal("50.00"), "SGD", null);

        doThrow(new org.springframework.security.access.AccessDeniedException("not yours"))
                .when(accountClient).verifyCallerOwnsAccount(from, "Bearer someone-elses-token");

        assertThatThrownBy(() -> transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer someone-elses-token", "key-0001"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void initiateTransfer_rejectsWhenVelocityLimitExceeded() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        TransferRequest request = new TransferRequest(from, to, new BigDecimal("50.00"), "SGD", null);

        doThrow(new VelocityLimitExceededException("too many"))
                .when(velocityLimitService).checkOrThrow(from, request.amount(), "FULL");

        assertThatThrownBy(() -> transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer test-token", "key-0001"))
                .isInstanceOf(VelocityLimitExceededException.class);

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void initiateTransfer_requiresStepUp_whenAmountAtOrAboveThreshold() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        // setUp() configures the threshold at 1000.00
        TransferRequest request = new TransferRequest(from, to, new BigDecimal("1000.00"), "SGD", null);

        UUID callerId = UUID.randomUUID();
        doThrow(new StepUpRequiredException("need step up"))
                .when(stepUpTokenValidator).requireValid(null, callerId);

        assertThatThrownBy(() -> transferService.initiateTransfer(request, callerId, "Bearer test-token", "key-0001"))
                .isInstanceOf(StepUpRequiredException.class);

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void initiateTransfer_belowThreshold_neverConsultsStepUpValidator() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        // setUp() configures the threshold at 1000.00 - this is comfortably under it.
        TransferRequest request = new TransferRequest(from, to, new BigDecimal("999.99"), "SGD", null);

        transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer test-token", "key-0001");

        verifyNoInteractions(stepUpTokenValidator);
    }

    @Test
    void initiateTransfer_atOrAboveThreshold_succeedsWithValidStepUpToken() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        TransferRequest request = new TransferRequest(from, to, new BigDecimal("5000.00"), "SGD", "valid-step-up-token");

        // stepUpTokenValidator.requireValid(...) is void and simply doesn't throw - that's "valid".
        Transaction tx = transferService.initiateTransfer(request, callerId, "Bearer test-token", "key-0001").transaction();

        verify(stepUpTokenValidator).requireValid("valid-step-up-token", callerId);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.DEBIT_PENDING);
    }
    @Test
    void onDebitCompleted_movesToCreditPending_andPublishesCreditRequest() {
        Transaction tx = transactionWithId(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("100.00"),
                "SGD"
        );
        tx.moveTo(TransactionStatus.DEBIT_PENDING);

        TransactionEvent event = TransactionEvent.of(
                UUID.randomUUID(),
                tx.getFromAccountId(),
                tx.getAmount(),
                "SGD"
        );

        when(transactionRepository.findById(any())).thenReturn(Optional.of(tx));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        transferService.onDebitCompleted(event);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.CREDIT_PENDING);

        verify(outboxEventRepository)
                .save(argThat(e -> e.getTopic().equals(KafkaTopics.CREDIT_REQUESTED)));
    }

    @Test
    void onDebitFailed_marksFailed_withNoCompensationNeeded() {
        Transaction tx = transactionWithId(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), "SGD");
        tx.moveTo(TransactionStatus.DEBIT_PENDING);

        TransactionEvent event = new TransactionEvent(UUID.randomUUID(), UUID.randomUUID(), tx.getFromAccountId(),
                tx.getAmount(), "SGD", "INSUFFICIENT_BALANCE", java.time.Instant.now());
        when(transactionRepository.findById(any())).thenReturn(Optional.of(tx));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        transferService.onDebitFailed(event);

        assertThat(meterRegistry.get("neobank.transfers.failed").tag("stage", "debit").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("neobank.transfers.saga.duration").tag("outcome", "failed").timer().count()).isEqualTo(1L);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureReason()).isEqualTo("INSUFFICIENT_BALANCE");
        // No compensation topic should ever be published here - the debit
        // never succeeded, so there's nothing on the sender's side to reverse.
        verify(outboxEventRepository, never()).save(argThat(e -> e.getTopic().equals(KafkaTopics.DEBIT_COMPENSATE)));
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals(KafkaTopics.TRANSACTION_FAILED)));
    }

    @Test
    void onCreditCompleted_marksCompleted() {
        Transaction tx = transactionWithId(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), "SGD");
        tx.moveTo(TransactionStatus.CREDIT_PENDING);

        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), tx.getToAccountId(), tx.getAmount(), "SGD");
        when(transactionRepository.findById(any())).thenReturn(Optional.of(tx));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        transferService.onCreditCompleted(event);

        assertThat(meterRegistry.get("neobank.transfers.completed").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("neobank.transfers.saga.duration").tag("outcome", "completed").timer().count()).isEqualTo(1L);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals(KafkaTopics.TRANSACTION_COMPLETED)));
    }

    @Test
    void onCreditFailed_triggersCompensation_andMarksFailed() {
        // This is the branch that makes it a real saga: the debit already
        // succeeded on the sender, so failing here MUST publish a compensating
        // debit.compensate event, or the sender's money is stuck in limbo.
        Transaction tx = transactionWithId(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"), "SGD");
        tx.moveTo(TransactionStatus.CREDIT_PENDING);

        TransactionEvent event = new TransactionEvent(UUID.randomUUID(), UUID.randomUUID(), tx.getToAccountId(),
                tx.getAmount(), "SGD", "ACCOUNT_FROZEN", java.time.Instant.now());
        when(transactionRepository.findById(any())).thenReturn(Optional.of(tx));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        transferService.onCreditFailed(event);

        assertThat(meterRegistry.get("neobank.transfers.compensated").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("neobank.transfers.failed").tag("stage", "credit").counter().count()).isEqualTo(1.0);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureReason()).isEqualTo("ACCOUNT_FROZEN");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(2)).save(captor.capture());
        List<String> publishedTopics = captor.getAllValues().stream().map(OutboxEvent::getTopic).toList();
        assertThat(publishedTopics).contains(KafkaTopics.DEBIT_COMPENSATE, KafkaTopics.TRANSACTION_FAILED);
    }

    @Test
    void sagaHandlers_areIdempotent_skipAlreadyProcessedEvents() {
        TransactionEvent event = TransactionEvent.of(
                UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        transferService.onDebitCompleted(event);

        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventRepository);
    }

    // ---- idempotency ----

    @Test
    void initiateTransfer_storesCallerAndIdempotencyKeyOnTheTransaction() {
        UUID caller = UUID.randomUUID();
        TransferRequest request = new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "SGD", null);

        Transaction tx = transferService.initiateTransfer(request, caller, "Bearer t", "order-42").transaction();

        assertThat(tx.getRequestedBy()).isEqualTo(caller);
        assertThat(tx.getIdempotencyKey()).isEqualTo("order-42");
        assertThat(tx.getRequestFingerprint()).hasSize(64);
    }

    @Test
    void initiateTransfer_replaysExistingTransaction_withoutMovingMoneyAgain() {
        UUID caller = UUID.randomUUID();
        TransferRequest request = new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "SGD", null);
        Transaction original = Transaction.initiate(request.fromAccountId(), request.toAccountId(), request.amount(),
                "SGD", caller, "order-42", TransferService.fingerprint(request));
        when(transactionRepository.findByRequestedByAndIdempotencyKey(caller, "order-42")).thenReturn(Optional.of(original));

        TransferService.TransferResult result = transferService.initiateTransfer(request, caller, "Bearer t", "order-42");

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isSameAs(original);
        // nothing is re-checked or re-published on a replay
        verify(transactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxEventRepository, accountClient, velocityLimitService, stepUpTokenValidator, socketNotifier);
        assertThat(meterRegistry.get("neobank.transfers.idempotent.replays").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("neobank.transfers.started").counter().count()).isZero();
    }

    @Test
    void initiateTransfer_sameKeyWithDifferentPayload_isRejected() {
        UUID caller = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        TransferRequest first = new TransferRequest(from, to, new BigDecimal("10.00"), "SGD", null);
        TransferRequest different = new TransferRequest(from, to, new BigDecimal("999.00"), "SGD", null);
        Transaction original = Transaction.initiate(from, to, first.amount(), "SGD", caller, "order-42",
                TransferService.fingerprint(first));
        when(transactionRepository.findByRequestedByAndIdempotencyKey(caller, "order-42")).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> transferService.initiateTransfer(different, caller, "Bearer t", "order-42"))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void replayExisting_answersFromTheWinnerOfAnIdempotencyRace() {
        UUID caller = UUID.randomUUID();
        TransferRequest request = new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "SGD", null);
        Transaction winner = Transaction.initiate(request.fromAccountId(), request.toAccountId(), request.amount(),
                "SGD", caller, "order-42", TransferService.fingerprint(request));
        when(transactionRepository.findByRequestedByAndIdempotencyKey(caller, "order-42")).thenReturn(Optional.of(winner));

        TransferService.TransferResult result = transferService.replayExisting(request, caller, "order-42");

        assertThat(result.replayed()).isTrue();
        assertThat(result.transaction()).isSameAs(winner);
    }

    @Test
    void initiateTransfer_requiresAnIdempotencyKey() {
        TransferRequest request = new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "SGD", null);

        assertThatThrownBy(() -> transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer t", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer t", "   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Idempotency-Key");
    }

    @Test
    void initiateTransfer_rejectsMalformedIdempotencyKey() {
        TransferRequest request = new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "SGD", null);

        assertThatThrownBy(() -> transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer t", "has spaces!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer t", "x".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprint_ignoresDecimalScaleAndCurrencyCase_butNotAmountOrAccounts() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        String base = TransferService.fingerprint(new TransferRequest(from, to, new BigDecimal("100"), "SGD", null));

        assertThat(TransferService.fingerprint(new TransferRequest(from, to, new BigDecimal("100.00"), "sgd", "any-token")))
                .isEqualTo(base);
        assertThat(TransferService.fingerprint(new TransferRequest(from, to, new BigDecimal("100.01"), "SGD", null)))
                .isNotEqualTo(base);
        assertThat(TransferService.fingerprint(new TransferRequest(to, from, new BigDecimal("100"), "SGD", null)))
                .isNotEqualTo(base);
    }

    // ---- saga metrics ----

    @Test
    void initiateTransfer_countsStartedTransfers() {
        TransferRequest request = new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "SGD", null);

        transferService.initiateTransfer(request, UUID.randomUUID(), "Bearer t", "key-metrics-1");

        assertThat(meterRegistry.get("neobank.transfers.started").counter().count()).isEqualTo(1.0);
    }
}
