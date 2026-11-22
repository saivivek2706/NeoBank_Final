package com.neobank.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.account.domain.Account;
import com.neobank.account.domain.JournalEntry;
import com.neobank.account.domain.JournalType;
import com.neobank.account.domain.AccountType;
import com.neobank.account.domain.OutboxEvent;
import com.neobank.account.kafka.KafkaTopics;
import com.neobank.account.kafka.event.TransactionEvent;
import com.neobank.account.domain.LedgerEntry;
import com.neobank.account.domain.LedgerEntryType;
import com.neobank.account.dto.CreateAccountRequest;
import com.neobank.account.kafka.event.ComplianceActionEvent;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.LedgerEntryRepository;
import com.neobank.account.repository.OutboxEventRepository;
import com.neobank.account.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private JournalService journalService;
    @Mock private HoldService holdService;

    private AccountService accountService;
    private SimpleMeterRegistry meterRegistry;
    private Account account;
    private final JournalEntry journal = JournalEntry.create(UUID.randomUUID(), JournalType.TRANSFER_DEBIT, "SGD", "test", null, null);

    private AccountService serviceWithFees(String flat) {
        return new AccountService(accountRepository, outboxEventRepository, processedEventRepository, ledgerEntryRepository,
                new ObjectMapper().findAndRegisterModules(), journalService, holdService,
                new FeeCalculator(new FeeProperties(new BigDecimal(flat), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)),
                meterRegistry);
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        accountService = serviceWithFees("0");
        lenient().when(holdService.heldAmount(any())).thenReturn(BigDecimal.ZERO);
        lenient().when(journalService.postOpeningDeposit(any(), any(), any(), any())).thenReturn(journal);
        lenient().when(journalService.postTransferDebit(any(), any(), any(), any())).thenReturn(journal);
        lenient().when(journalService.postTransferCredit(any(), any(), any())).thenReturn(journal);
        account = Account.open("NB100000002", UUID.randomUUID(), "Bob Lee",
                AccountType.SAVINGS, "SGD", new BigDecimal("500.00"));
        org.springframework.test.util.ReflectionTestUtils.setField(
                account, "id", UUID.randomUUID());
    }

    @Test
    void handleDebitRequested_debitsAccount_andPublishesCompletedEvent() {
        UUID txId = UUID.randomUUID();
        TransactionEvent event = TransactionEvent.of(txId, account.getId(), new BigDecimal("100.00"), "SGD");

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleDebitRequested(event);

        assertThat(account.getBalance()).isEqualByComparingTo("400.00");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getTopic()).isEqualTo(KafkaTopics.DEBIT_COMPLETED);
        assertThat(outboxCaptor.getValue().getStatus().name()).isEqualTo("PENDING");

        verify(processedEventRepository).save(any());

        ArgumentCaptor<LedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("100.00");
        assertThat(ledgerCaptor.getValue().getBalanceAfter()).isEqualByComparingTo("400.00");
        assertThat(ledgerCaptor.getValue().getTransactionId()).isEqualTo(txId);
    }

    @Test
    void handleDebitRequested_publishesFailedEvent_whenInsufficientBalance() {
        UUID txId = UUID.randomUUID();
        TransactionEvent event = TransactionEvent.of(txId, account.getId(), new BigDecimal("999999.00"), "SGD");

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleDebitRequested(event);

        // Balance must be unchanged - the debit never actually happened.
        assertThat(account.getBalance()).isEqualByComparingTo("500.00");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getTopic()).isEqualTo(KafkaTopics.DEBIT_FAILED);

        // A debit that never actually happened must not leave a ledger trace.
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void handleDebitRequested_isIdempotent_skipsAlreadyProcessedEvent() {
        UUID txId = UUID.randomUUID();
        TransactionEvent event = TransactionEvent.of(txId, account.getId(), new BigDecimal("100.00"), "SGD");

        // Simulate Kafka redelivering a message we already handled.
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        accountService.handleDebitRequested(event);

        // Must not touch the account or publish anything a second time.
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void handleCreditRequested_creditsAccount_andPublishesCompletedEvent() {
        UUID txId = UUID.randomUUID();
        TransactionEvent event = TransactionEvent.of(txId, account.getId(), new BigDecimal("250.00"), "SGD");

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleCreditRequested(event);

        assertThat(account.getBalance()).isEqualByComparingTo("750.00");
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals(KafkaTopics.CREDIT_COMPLETED)));

        ArgumentCaptor<LedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("250.00");
    }

    @Test
    void handleCreditRequested_unknownAccount_failsTheStepSoTheDebitGetsCompensated() {
        UUID missing = UUID.randomUUID();
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), missing, new BigDecimal("10.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        accountService.handleCreditRequested(event);   // must NOT throw: throwing would dead-letter it and strand the transfer

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getTopic()).isEqualTo(KafkaTopics.CREDIT_FAILED);
        assertThat(captor.getValue().getPayload()).contains("ACCOUNT_NOT_FOUND");
        verify(processedEventRepository).save(any());
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void handleDebitRequested_unknownAccount_publishesDebitFailed() {
        UUID missing = UUID.randomUUID();
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), missing, new BigDecimal("10.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        accountService.handleDebitRequested(event);

        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals(KafkaTopics.DEBIT_FAILED)));
        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void openAccount_withOpeningBalance_writesOpeningDepositLedgerEntry() {
        UUID ownerId = UUID.randomUUID();
        CreateAccountRequest request = new CreateAccountRequest(ownerId, "New Owner", AccountType.SAVINGS,
                "SGD", new BigDecimal("1000.00"));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Account opened = accountService.openAccount(request);

        assertThat(opened.getBalance()).isEqualByComparingTo("1000.00");
        ArgumentCaptor<LedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(ledgerCaptor.getValue().getDescription()).isEqualTo("Opening deposit");
        assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void openAccount_withZeroOpeningBalance_writesNoLedgerEntry() {
        UUID ownerId = UUID.randomUUID();
        CreateAccountRequest request = new CreateAccountRequest(ownerId, "New Owner", AccountType.SAVINGS,
                "SGD", BigDecimal.ZERO);
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.openAccount(request);

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void handleDebitCompensate_restoresBalance() {
        account.debit(new BigDecimal("200.00")); // simulate the original debit that needs reversing
        UUID txId = UUID.randomUUID();
        TransactionEvent event = TransactionEvent.of(txId, account.getId(), new BigDecimal("200.00"), "SGD");

        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(journalService.postTransferReversal(account, txId, new BigDecimal("200.00")))
                .thenReturn(new JournalService.ReversalResult(journal, new BigDecimal("200.00"), BigDecimal.ZERO));

        accountService.handleDebitCompensate(event);

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");

        ArgumentCaptor<LedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getEntryType()).isEqualTo(LedgerEntryType.CREDIT);
        assertThat(ledgerCaptor.getValue().getDescription()).contains("Compensating reversal");
    }

    // ---- double-entry journal wiring ----

    @Test
    void handleDebitRequested_postsTheDebitToTheJournal_withNoFeeByDefault() {
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), account.getId(), new BigDecimal("100.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleDebitRequested(event);

        verify(journalService).postTransferDebit(account, event.transactionId(), new BigDecimal("100.00"), BigDecimal.ZERO);
    }

    @Test
    void handleDebitRequested_chargesTheFeeOnTopOfTheAmount_andRecordsItAsItsOwnStatementLine() {
        accountService = serviceWithFees("1.50");
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), account.getId(), new BigDecimal("100.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleDebitRequested(event);

        assertThat(account.getBalance()).isEqualByComparingTo("398.50");   // 500 - 100 - 1.50
        verify(journalService).postTransferDebit(account, event.transactionId(), new BigDecimal("100.00"), new BigDecimal("1.50"));

        ArgumentCaptor<LedgerEntry> ledger = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(ledger.capture());
        assertThat(ledger.getAllValues()).extracting(LedgerEntry::getDescription).containsExactly("Transfer debit", "Transfer fee");
        assertThat(ledger.getAllValues().get(0).getBalanceAfter()).isEqualByComparingTo("400.00");
        assertThat(ledger.getAllValues().get(1).getBalanceAfter()).isEqualByComparingTo("398.50");
        assertThat(meterRegistry.get("neobank.fees.collected").tag("currency", "SGD").counter().count()).isEqualTo(1.5);
    }

    @Test
    void handleDebitRequested_rejectsWhenTheFeeWouldOverdrawTheAccount() {
        accountService = serviceWithFees("10.00");
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), account.getId(), new BigDecimal("495.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleDebitRequested(event);   // 495 + 10 > 500

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals(KafkaTopics.DEBIT_FAILED)));
        verifyNoInteractions(journalService);
    }

    @Test
    void handleDebitRequested_cannotSpendMoneyThatIsOnHold() {
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), account.getId(), new BigDecimal("100.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(holdService.heldAmount(account.getId())).thenReturn(new BigDecimal("450.00"));   // only 50.00 available

        accountService.handleDebitRequested(event);

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals(KafkaTopics.DEBIT_FAILED)
                && e.getPayload().contains("Insufficient available balance")));
        verifyNoInteractions(journalService);
    }

    @Test
    void handleDebitRequested_aJournalFailureRollsEverythingBack_insteadOfBeingReportedAsABusinessFailure() {
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), account.getId(), new BigDecimal("100.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(journalService.postTransferDebit(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Unbalanced journal"));

        // Must propagate (-> transaction rollback + retry/DLT): swallowing it would debit the account with no journal.
        assertThatThrownBy(() -> accountService.handleDebitRequested(event)).hasMessageContaining("Unbalanced journal");

        verifyNoInteractions(outboxEventRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void handleCreditRequested_postsTheCreditToTheJournal() {
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), account.getId(), new BigDecimal("250.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleCreditRequested(event);

        verify(journalService).postTransferCredit(account, event.transactionId(), new BigDecimal("250.00"));
    }

    @Test
    void handleCreditRequested_frozenReceiver_failsTheStep_withoutPostingAnything() {
        account.freeze();
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), account.getId(), new BigDecimal("250.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleCreditRequested(event);

        verify(outboxEventRepository).save(argThat(e -> e.getTopic().equals(KafkaTopics.CREDIT_FAILED)));
        verifyNoInteractions(journalService, ledgerEntryRepository);
        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void handleDebitCompensate_refundsTheFeeToo() {
        account.debit(new BigDecimal("201.00"));   // original debit: 200.00 + 1.00 fee
        TransactionEvent event = TransactionEvent.of(UUID.randomUUID(), account.getId(), new BigDecimal("200.00"), "SGD");
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(journalService.postTransferReversal(account, event.transactionId(), new BigDecimal("200.00")))
                .thenReturn(new JournalService.ReversalResult(journal, new BigDecimal("200.00"), new BigDecimal("1.00")));

        accountService.handleDebitCompensate(event);

        assertThat(account.getBalance()).isEqualByComparingTo("500.00");   // fully restored, fee included
        ArgumentCaptor<LedgerEntry> ledger = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository, times(2)).save(ledger.capture());
        assertThat(ledger.getAllValues()).extracting(LedgerEntry::getDescription)
                .containsExactly("Compensating reversal for failed transfer", "Fee refund for failed transfer");
    }

    @Test
    void openAccount_postsTheOpeningDepositToTheJournal_withTheFundingReference() {
        CreateAccountRequest request = new CreateAccountRequest(UUID.randomUUID(), "New Owner", AccountType.SAVINGS,
                "SGD", new BigDecimal("1000.00"), "BANK-REF-77");
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.openAccount(request);

        verify(journalService).postOpeningDeposit(any(), any(), eq(new BigDecimal("1000.00")), eq("BANK-REF-77"));
    }

    // ---- compliance integration ----

    @Test
    void openAccount_announcesTheNewPartyForSanctionsScreening() {
        UUID ownerId = UUID.randomUUID();
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        accountService.openAccount(new CreateAccountRequest(ownerId, "Jane Doe", AccountType.SAVINGS, "SGD", BigDecimal.ZERO));

        ArgumentCaptor<OutboxEvent> outbox = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outbox.capture());
        assertThat(outbox.getValue().getTopic()).isEqualTo(KafkaTopics.ACCOUNT_OPENED);
        assertThat(outbox.getValue().getPayload()).contains("Jane Doe").contains(ownerId.toString());
        // screening needs a name and ids only - never balances or account numbers
        assertThat(outbox.getValue().getPayload()).doesNotContain("NB").doesNotContain("balance");
    }

    private ComplianceActionEvent action(ComplianceActionEvent.Action action) {
        return new ComplianceActionEvent(UUID.randomUUID(), account.getId(), action, "sanctions hit", UUID.randomUUID(),
                java.time.Instant.now());
    }

    @Test
    void complianceFreeze_freezesAnActiveAccount() {
        ComplianceActionEvent event = action(ComplianceActionEvent.Action.FREEZE);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleComplianceAction(event);

        assertThat(account.getStatus()).isEqualTo(com.neobank.account.domain.AccountStatus.FROZEN);
        verify(accountRepository).save(account);
        verify(processedEventRepository).save(any());
        assertThat(meterRegistry.get("neobank.compliance.account.frozen").counter().count()).isEqualTo(1.0);
    }

    @Test
    void complianceUnfreeze_restoresAFrozenAccount() {
        account.freeze();
        ComplianceActionEvent event = action(ComplianceActionEvent.Action.UNFREEZE);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleComplianceAction(event);

        assertThat(account.getStatus()).isEqualTo(com.neobank.account.domain.AccountStatus.ACTIVE);
    }

    @Test
    void complianceUnfreeze_doesNothingToAnAccountThatIsNotFrozen() {
        ComplianceActionEvent event = action(ComplianceActionEvent.Action.UNFREEZE);
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));

        accountService.handleComplianceAction(event);

        assertThat(account.getStatus()).isEqualTo(com.neobank.account.domain.AccountStatus.ACTIVE);
        verify(accountRepository, never()).save(any());
    }

    @Test
    void complianceCommands_areIdempotent_andTolerateUnknownAccounts() {
        ComplianceActionEvent seen = action(ComplianceActionEvent.Action.FREEZE);
        when(processedEventRepository.existsById(seen.eventId())).thenReturn(true);
        accountService.handleComplianceAction(seen);
        verifyNoInteractions(accountRepository);

        ComplianceActionEvent unknown = new ComplianceActionEvent(UUID.randomUUID(), UUID.randomUUID(),
                ComplianceActionEvent.Action.FREEZE, "x", null, java.time.Instant.now());
        when(processedEventRepository.existsById(unknown.eventId())).thenReturn(false);
        when(accountRepository.findByIdForUpdate(unknown.accountId())).thenReturn(Optional.empty());
        accountService.handleComplianceAction(unknown);   // must not throw - there is nothing to freeze

        verify(processedEventRepository).save(any());
    }
}
