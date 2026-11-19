package com.neobank.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.account.domain.*;
import com.neobank.account.dto.CreateAccountRequest;
import com.neobank.account.exception.AccountNotFoundException;
import com.neobank.account.kafka.KafkaTopics;
import com.neobank.account.kafka.event.AccountOpenedEvent;
import com.neobank.account.kafka.event.ComplianceActionEvent;
import com.neobank.account.kafka.event.TransactionEvent;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.LedgerEntryRepository;
import com.neobank.account.repository.OutboxEventRepository;
import com.neobank.account.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ObjectMapper objectMapper;
    private final JournalService journalService;
    private final HoldService holdService;
    private final FeeCalculator feeCalculator;
    private final MeterRegistry meterRegistry;

    /**
     * Explicit constructor keeps dependency wiring unambiguous for both Spring and unit tests.
     */
    public AccountService(AccountRepository accountRepository,
                          OutboxEventRepository outboxEventRepository,
                          ProcessedEventRepository processedEventRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          ObjectMapper objectMapper,
                          JournalService journalService,
                          HoldService holdService,
                          FeeCalculator feeCalculator,
                          MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.objectMapper = objectMapper;
        this.journalService = journalService;
        this.holdService = holdService;
        this.feeCalculator = feeCalculator;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public Account openAccount(CreateAccountRequest req) {
        String accountNumber = generateAccountNumber();
        BigDecimal openingBalance = req.openingBalance() != null ? req.openingBalance() : BigDecimal.ZERO;
        Account account = Account.open(accountNumber, req.ownerId(), req.ownerName(),
                req.accountType(), req.currency(), openingBalance);
        accountRepository.save(account);

        if (openingBalance.compareTo(BigDecimal.ZERO) > 0) {
            // The opening deposit is itself a ledger-worthy event, not just an initial value: it is posted to the
            // double-entry journal (Dr EXTERNAL_FUNDING / Cr customer) and gets a statement line. A brand new UUID
            // stands in for "transaction id" since there's no transfer saga behind it.
            UUID depositId = UUID.randomUUID();
            JournalEntry journal = journalService.postOpeningDeposit(account, depositId, openingBalance, req.fundingReference());
            writeLedgerEntry(account, depositId, LedgerEntryType.CREDIT, openingBalance, account.getBalance(),
                    "Opening deposit", journal.getId());
        }
        // Tell the compliance service a new party exists so its owner is screened against sanctions watchlists.
        publishJson(KafkaTopics.ACCOUNT_OPENED, "Account", String.valueOf(account.getId()), "AccountOpened",
                AccountOpenedEvent.of(account.getId(), account.getOwnerId(), account.getOwnerName(), account.getCurrency()));
        return account;
    }

    /**
     * Applies a command from the compliance service. FREEZE only affects an ACTIVE account and UNFREEZE only a FROZEN
     * one, so a CLOSED account is never resurrected. Idempotent per eventId like every other consumer here.
     */
    @Transactional
    public void handleComplianceAction(ComplianceActionEvent event) {
        if (alreadyProcessed(event.eventId())) return;

        Optional<Account> found = accountRepository.findByIdForUpdate(event.accountId());
        if (found.isPresent()) {
            Account account = found.get();
            if (event.action() == ComplianceActionEvent.Action.FREEZE && account.getStatus() == AccountStatus.ACTIVE) {
                account.freeze();
                accountRepository.save(account);
                meterRegistry.counter("neobank.compliance.account.frozen").increment();
            } else if (event.action() == ComplianceActionEvent.Action.UNFREEZE && account.getStatus() == AccountStatus.FROZEN) {
                account.unfreeze();
                accountRepository.save(account);
                meterRegistry.counter("neobank.compliance.account.unfrozen").increment();
            }
        }
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    @Transactional(readOnly = true)
    public Account getById(UUID id) {
        return accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Account getByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    @Transactional(readOnly = true)
    public java.util.List<Account> getByOwnerId(UUID ownerId) {
        return accountRepository.findByOwnerId(ownerId);
    }

    /**
     * Handles an `account.debit.requested` saga step.
     * Idempotent: if this eventId was already processed (e.g. Kafka redelivered
     * the message after a consumer rebalance), we skip straight to re-emitting
     * the outcome without touching the balance twice.
     */
    @Transactional
    public void handleDebitRequested(TransactionEvent event) {
        if (alreadyProcessed(event.eventId())) return;

        Optional<Account> found = accountRepository.findByIdForUpdate(event.accountId());
        if (found.isEmpty()) {
            // An unknown account can never succeed on a retry. Throwing here would only dead-letter the event
            // and leave the transfer stuck mid-saga (with the sender already debited, for a missing receiver).
            // Failing the saga step explicitly lets the transaction-service fail the transfer - and, for a
            // failed credit, compensate the debit.
            publish(KafkaTopics.DEBIT_FAILED, "Account", event.accountId().toString(), "DebitFailed",
                    TransactionEvent.failed(event.transactionId(), event.accountId(), event.amount(), event.currency(),
                            "ACCOUNT_NOT_FOUND"));
            processedEventRepository.save(new ProcessedEvent(event.eventId()));
            return;
        }
        Account account = found.get();

        // The sender pays the transfer amount plus any fee, and may only spend what is AVAILABLE (balance minus
        // active holds). Only genuine business rejections are caught here; a failure while posting to the
        // journal below is a bug or an outage and must roll the whole transaction back instead.
        BigDecimal fee = feeCalculator.feeFor(event.amount(), account.getCurrency());
        BigDecimal total = event.amount().add(fee);
        try {
            requireAvailable(account, total);
            account.debit(total);
        } catch (IllegalStateException ex) {
            publish(KafkaTopics.DEBIT_FAILED, "Account", account.getId().toString(), "DebitFailed",
                    TransactionEvent.failed(event.transactionId(), event.accountId(), event.amount(), event.currency(), ex.getMessage()));
            processedEventRepository.save(new ProcessedEvent(event.eventId()));
            return;
        }

        accountRepository.save(account);
        // Journal + statement lines are written in the SAME transaction as the balance mutation above - they
        // either all commit or all roll back, so the cached balance, the statement and the double-entry journal
        // can never disagree because of a partial write (only a genuine logic bug, which LedgerAuditService and
        // LedgerReconciliationService exist to catch).
        JournalEntry journal = journalService.postTransferDebit(account, event.transactionId(), event.amount(), fee);
        if (fee.signum() > 0) {
            writeLedgerEntry(account, event.transactionId(), LedgerEntryType.DEBIT, event.amount(),
                    account.getBalance().add(fee), "Transfer debit", journal.getId());
            writeLedgerEntry(account, event.transactionId(), LedgerEntryType.DEBIT, fee,
                    account.getBalance(), "Transfer fee", journal.getId());
            meterRegistry.counter("neobank.fees.collected", "currency", account.getCurrency()).increment(fee.doubleValue());
        } else {
            writeLedgerEntry(account, event.transactionId(), LedgerEntryType.DEBIT, event.amount(),
                    account.getBalance(), "Transfer debit", journal.getId());
        }
        publish(KafkaTopics.DEBIT_COMPLETED, "Account", account.getId().toString(), "DebitCompleted",
                TransactionEvent.of(event.transactionId(), event.accountId(), event.amount(), event.currency()));
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    /** Handles an `account.credit.requested` saga step. */
    @Transactional
    public void handleCreditRequested(TransactionEvent event) {
        if (alreadyProcessed(event.eventId())) return;

        Optional<Account> found = accountRepository.findByIdForUpdate(event.accountId());
        if (found.isEmpty()) {
            // An unknown account can never succeed on a retry. Throwing here would only dead-letter the event
            // and leave the transfer stuck mid-saga (with the sender already debited, for a missing receiver).
            // Failing the saga step explicitly lets the transaction-service fail the transfer - and, for a
            // failed credit, compensate the debit.
            publish(KafkaTopics.CREDIT_FAILED, "Account", event.accountId().toString(), "CreditFailed",
                    TransactionEvent.failed(event.transactionId(), event.accountId(), event.amount(), event.currency(),
                            "ACCOUNT_NOT_FOUND"));
            processedEventRepository.save(new ProcessedEvent(event.eventId()));
            return;
        }
        Account account = found.get();

        try {
            account.credit(event.amount());
        } catch (IllegalStateException ex) {   // e.g. receiver is FROZEN/CLOSED -> the saga compensates the debit
            publish(KafkaTopics.CREDIT_FAILED, "Account", account.getId().toString(), "CreditFailed",
                    TransactionEvent.failed(event.transactionId(), event.accountId(), event.amount(), event.currency(), ex.getMessage()));
            processedEventRepository.save(new ProcessedEvent(event.eventId()));
            return;
        }

        accountRepository.save(account);
        JournalEntry journal = journalService.postTransferCredit(account, event.transactionId(), event.amount());
        writeLedgerEntry(account, event.transactionId(), LedgerEntryType.CREDIT, event.amount(),
                account.getBalance(), "Transfer credit", journal.getId());
        publish(KafkaTopics.CREDIT_COMPLETED, "Account", account.getId().toString(), "CreditCompleted",
                TransactionEvent.of(event.transactionId(), event.accountId(), event.amount(), event.currency()));
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    /**
     * Compensating transaction: undoes a debit when the matching credit on the
     * receiving account failed downstream. This is what makes the saga a real
     * saga rather than a hope-for-the-best two-step process.
     */
    @Transactional
    public void handleDebitCompensate(TransactionEvent event) {
        if (alreadyProcessed(event.eventId())) return;

        Account account = accountRepository.findByIdForUpdate(event.accountId())
                .orElseThrow(() -> new AccountNotFoundException(event.accountId()));

        // The reversal is the exact mirror of the original debit journal, so the sender gets back the amount AND
        // any fee. It is itself a real, ledger-worthy movement (money coming back in) - not an edit or deletion of
        // the original debit, which stays exactly as it was written. Append-only, always.
        JournalService.ReversalResult reversal =
                journalService.postTransferReversal(account, event.transactionId(), event.amount());
        account.reverseDebit(reversal.total());
        accountRepository.save(account);
        UUID journalId = reversal.journal().getId();
        if (reversal.fee().signum() > 0) {
            writeLedgerEntry(account, event.transactionId(), LedgerEntryType.CREDIT, reversal.principal(),
                    account.getBalance().subtract(reversal.fee()), "Compensating reversal for failed transfer", journalId);
            writeLedgerEntry(account, event.transactionId(), LedgerEntryType.CREDIT, reversal.fee(),
                    account.getBalance(), "Fee refund for failed transfer", journalId);
        } else {
            writeLedgerEntry(account, event.transactionId(), LedgerEntryType.CREDIT, reversal.principal(),
                    account.getBalance(), "Compensating reversal for failed transfer", journalId);
        }
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    /** Spendable money = balance minus active holds. A FROZEN/CLOSED account is rejected with its own message by Account. */
    private void requireAvailable(Account account, BigDecimal required) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            return;
        }
        BigDecimal available = account.getBalance().subtract(holdService.heldAmount(account.getId()));
        if (available.compareTo(required) < 0) {
            throw new IllegalStateException("Insufficient available balance on account " + account.getAccountNumber());
        }
    }

    private void writeLedgerEntry(Account account, UUID transactionId, LedgerEntryType type, BigDecimal amount,
                                  BigDecimal balanceAfter, String description, UUID journalId) {
        ledgerEntryRepository.save(LedgerEntry.of(account.getId(), transactionId, type, amount, balanceAfter,
                description, journalId));
    }

    private boolean alreadyProcessed(UUID eventId) {
        return processedEventRepository.existsById(eventId);
    }

    private void publish(String topic, String aggregateType, String aggregateId, String eventType, TransactionEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            outboxEventRepository.save(OutboxEvent.create(aggregateType, aggregateId, eventType, topic, json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize transaction event for outbox", ex);
        }
    }

    private void publishJson(String topic, String aggregateType, String aggregateId, String eventType, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            outboxEventRepository.save(OutboxEvent.create(aggregateType, aggregateId, eventType, topic, json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize event for outbox", ex);
        }
    }

    private String generateAccountNumber() {
        // Simple demo generator: 12-digit numeric account number, prefixed to look bank-like.
        long rand = ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
        return "NB" + rand;
    }
}
