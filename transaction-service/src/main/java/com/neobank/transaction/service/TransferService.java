package com.neobank.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.transaction.client.AccountClient;
import com.neobank.transaction.domain.OutboxEvent;
import com.neobank.transaction.domain.ProcessedEvent;
import com.neobank.transaction.domain.Transaction;
import com.neobank.transaction.domain.TransactionStatus;
import com.neobank.transaction.dto.TransferRequest;
import com.neobank.transaction.exception.IdempotencyKeyReuseException;
import com.neobank.transaction.kafka.KafkaTopics;
import com.neobank.transaction.kafka.event.TransactionEvent;
import com.neobank.transaction.metrics.SagaMetrics;
import com.neobank.transaction.repository.OutboxEventRepository;
import com.neobank.transaction.repository.ProcessedEventRepository;
import com.neobank.transaction.repository.TransactionRepository;
import com.neobank.transaction.websocket.TransactionSocketNotifier;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orchestrates the transfer saga via choreography: this service reacts to
 * each event published by account-service and decides what happens next.
 * Every state transition + the outbox event that drives the next step are
 * written in a single local DB transaction, so the saga can never "lose"
 * a step even if this process crashes right after committing.
 */
@Service
@Slf4j
public class TransferService {

    /** Result of initiating a transfer: the transaction, and whether it was an idempotent replay of an earlier request. */
    public record TransferResult(Transaction transaction, boolean replayed) {}

    private static final Pattern IDEMPOTENCY_KEY_FORMAT = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionSocketNotifier socketNotifier;
    private final AccountClient accountClient;
    private final VelocityLimitService velocityLimitService;
    private final StepUpTokenValidator stepUpTokenValidator;
    private final SagaMetrics sagaMetrics;
    private final BigDecimal stepUpThresholdAmount;

    // Explicit constructor (rather than Lombok @RequiredArgsConstructor) so
    // stepUpThresholdAmount is a real constructor dependency - anyone building
    // a TransferService (Spring, or a unit test) must supply it explicitly
    // instead of silently getting null/zero, which would make every transfer
    // require step-up or none at all depending on comparison direction.
    public TransferService(TransactionRepository transactionRepository,
                            OutboxEventRepository outboxEventRepository,
                            ProcessedEventRepository processedEventRepository,
                            ObjectMapper objectMapper,
                            TransactionSocketNotifier socketNotifier,
                            AccountClient accountClient,
                            VelocityLimitService velocityLimitService,
                            StepUpTokenValidator stepUpTokenValidator,
                            SagaMetrics sagaMetrics,
                            @Value("${transaction.step-up.threshold-amount}") BigDecimal stepUpThresholdAmount) {
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
        this.socketNotifier = socketNotifier;
        this.accountClient = accountClient;
        this.velocityLimitService = velocityLimitService;
        this.stepUpTokenValidator = stepUpTokenValidator;
        this.sagaMetrics = sagaMetrics;
        this.stepUpThresholdAmount = stepUpThresholdAmount;
    }

    /**
     * @param callerUserId   the authenticated caller (JWT `sub`) - used to verify they own fromAccountId, and to bind any step-up token to them specifically
     * @param bearerToken    the caller's own "Bearer xxx" Authorization header value, forwarded to account-service to check ownership as that same caller
     * @param idempotencyKey client-chosen unique key for this logical transfer (Idempotency-Key header). Retrying with the same key returns the original
     *                       transaction instead of moving money a second time; the same key with a different payload is rejected.
     */
    @Transactional
    public TransferResult initiateTransfer(TransferRequest request, UUID callerUserId, String bearerToken,
                                           String idempotencyKey) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new IllegalArgumentException("Cannot transfer to the same account");
        }

        String key = requireValidIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprint(request);

        // 0. Idempotent replay: the caller already submitted this exact transfer. Answer from the
        // stored transaction BEFORE the ownership/velocity/step-up checks - those already passed
        // the first time, and re-running them could wrongly reject a legitimate retry (e.g. the
        // attempt counter now being higher, or the single-use step-up token already consumed).
        Optional<Transaction> existing = transactionRepository.findByRequestedByAndIdempotencyKey(callerUserId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        // 1. Authorization: does the caller actually own the source account?
        // Without this, any authenticated customer could move money out of
        // ANY account by supplying its id - authentication alone proves who
        // you are, not that you're allowed to touch this specific account.
        accountClient.verifyCallerOwnsAccount(request.fromAccountId(), bearerToken);

        // 2. Velocity limits: how much / how often has this account sent recently.
        velocityLimitService.checkOrThrow(request.fromAccountId(), request.amount());

        // 3. Step-up MFA for high-value transfers: a valid password-authenticated
        // session is not, on its own, considered sufficient to move a large sum -
        // this mirrors how real banks require a fresh second factor for large
        // transfers even within an already-logged-in session.
        if (request.amount().compareTo(stepUpThresholdAmount) >= 0) {
            stepUpTokenValidator.requireValid(request.stepUpToken(), callerUserId);
        }

        Transaction tx = Transaction.initiate(request.fromAccountId(), request.toAccountId(),
                request.amount(), request.currency(), callerUserId, key, fingerprint);
        tx.moveTo(TransactionStatus.DEBIT_PENDING);
        // saveAndFlush (not save): the unique (requested_by, idempotency_key) index must fire HERE,
        // before the outbox row is written and the WebSocket push is sent, so a request that lost an
        // idempotency race has no side effects at all.
        transactionRepository.saveAndFlush(tx);

        publish(KafkaTopics.DEBIT_REQUESTED, tx.getId(), "TransferInitiated",
                TransactionEvent.of(tx.getId(), tx.getFromAccountId(), tx.getAmount(), tx.getCurrency()));

        socketNotifier.notifyStatus(tx);
        sagaMetrics.started();
        return new TransferResult(tx, false);
    }

    /**
     * Called by the controller when two requests with the same key raced and the database's unique
     * index rejected the loser: the winner's transaction is committed by now, so answer from it.
     */
    @Transactional(readOnly = true)
    public TransferResult replayExisting(TransferRequest request, UUID callerUserId, String idempotencyKey) {
        String key = requireValidIdempotencyKey(idempotencyKey);
        Transaction existing = transactionRepository.findByRequestedByAndIdempotencyKey(callerUserId, key)
                .orElseThrow(() -> new NoSuchElementException("No transfer found for idempotency key"));
        return replay(existing, fingerprint(request));
    }

    private TransferResult replay(Transaction existing, String fingerprint) {
        if (existing.getRequestFingerprint() != null && !existing.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReuseException(
                    "This Idempotency-Key was already used for a different transfer; use a new key for a new transfer");
        }
        sagaMetrics.idempotentReplay();
        return new TransferResult(existing, true);
    }

    private static String requireValidIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required for transfers");
        }
        String trimmed = key.trim();
        if (!IDEMPOTENCY_KEY_FORMAT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be 1-100 characters of letters, digits, '-', '_', '.' or ':'");
        }
        return trimmed;
    }

    /** SHA-256 over the business fields only. The step-up token is excluded: a retry legitimately carries a fresh one. */
    static String fingerprint(TransferRequest request) {
        String canonical = request.fromAccountId() + "|" + request.toAccountId() + "|"
                + request.amount().stripTrailingZeros().toPlainString() + "|"
                + request.currency().toUpperCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by every JVM", e);
        }
    }

    @Transactional
    public void onDebitCompleted(TransactionEvent event) {
        if (alreadyProcessed(event.eventId())) return;
        Transaction tx = getTransaction(event.transactionId());

        tx.moveTo(TransactionStatus.CREDIT_PENDING);
        transactionRepository.save(tx);

        publish(KafkaTopics.CREDIT_REQUESTED, tx.getId(), "CreditRequested",
                TransactionEvent.of(tx.getId(), tx.getToAccountId(), tx.getAmount(), tx.getCurrency()));

        markProcessed(event.eventId());
        socketNotifier.notifyStatus(tx);
    }

    @Transactional
    public void onDebitFailed(TransactionEvent event) {
        if (alreadyProcessed(event.eventId())) return;
        Transaction tx = getTransaction(event.transactionId());

        // Sender never lost funds (debit itself failed), so there is nothing to
        // compensate - straight to FAILED.
        tx.fail(event.reason() != null ? event.reason() : "DEBIT_FAILED");
        transactionRepository.save(tx);

        publish(KafkaTopics.TRANSACTION_FAILED, tx.getId(), "TransactionFailed",
                TransactionEvent.of(tx.getId(), tx.getFromAccountId(), tx.getAmount(), tx.getCurrency()));

        markProcessed(event.eventId());
        socketNotifier.notifyStatus(tx);
        sagaMetrics.failed("debit", tx.getCreatedAt());
    }

    @Transactional
    public void onCreditCompleted(TransactionEvent event) {
        if (alreadyProcessed(event.eventId())) return;
        Transaction tx = getTransaction(event.transactionId());

        tx.moveTo(TransactionStatus.COMPLETED);
        transactionRepository.save(tx);

        publish(KafkaTopics.TRANSACTION_COMPLETED, tx.getId(), "TransactionCompleted",
                TransactionEvent.of(tx.getId(), tx.getToAccountId(), tx.getAmount(), tx.getCurrency()));

        markProcessed(event.eventId());
        socketNotifier.notifyStatus(tx);
        sagaMetrics.completed(tx.getCreatedAt());
    }

    /**
     * The compensating branch: the debit on the sender succeeded, but crediting
     * the receiver failed (e.g. receiver account frozen/closed). We must reverse
     * the debit so money isn't stuck in limbo - this is the whole point of a saga
     * over a naive two-step process.
     */
    @Transactional
    public void onCreditFailed(TransactionEvent event) {
        if (alreadyProcessed(event.eventId())) return;
        Transaction tx = getTransaction(event.transactionId());

        tx.moveTo(TransactionStatus.COMPENSATING);
        transactionRepository.save(tx);

        publish(KafkaTopics.DEBIT_COMPENSATE, tx.getId(), "DebitCompensationRequested",
                TransactionEvent.of(tx.getId(), tx.getFromAccountId(), tx.getAmount(), tx.getCurrency()));

        tx.fail(event.reason() != null ? event.reason() : "CREDIT_FAILED");
        transactionRepository.save(tx);

        publish(KafkaTopics.TRANSACTION_FAILED, tx.getId(), "TransactionFailed",
                TransactionEvent.of(tx.getId(), tx.getFromAccountId(), tx.getAmount(), tx.getCurrency()));

        markProcessed(event.eventId());
        socketNotifier.notifyStatus(tx);
        sagaMetrics.compensated();
        sagaMetrics.failed("credit", tx.getCreatedAt());
    }

    private Transaction getTransaction(UUID id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Transaction not found: " + id));
    }

    private boolean alreadyProcessed(UUID eventId) {
        return processedEventRepository.existsById(eventId);
    }

    private void markProcessed(UUID eventId) {
        processedEventRepository.save(new ProcessedEvent(eventId));
    }

    @SneakyThrows
    private void publish(String topic, UUID aggregateId, String eventType, TransactionEvent event) {
        String json = objectMapper.writeValueAsString(event);
        outboxEventRepository.save(OutboxEvent.create("Transaction", aggregateId.toString(), eventType, topic, json));
    }
}
