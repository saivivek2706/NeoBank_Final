package com.neobank.account.service;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountType;
import com.neobank.account.domain.GlAccount;
import com.neobank.account.domain.JournalEntry;
import com.neobank.account.domain.JournalType;
import com.neobank.account.domain.Posting;
import com.neobank.account.domain.PostingDirection;
import com.neobank.account.domain.SystemAccount;
import com.neobank.account.repository.JournalEntryRepository;
import com.neobank.account.repository.PostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalServiceTest {

    @Mock private JournalEntryRepository journalEntryRepository;
    @Mock private PostingRepository postingRepository;
    @Mock private GlAccountService glAccountService;

    private JournalService journalService;
    private Account account;
    private final UUID settlementId = UUID.randomUUID();
    private final UUID feeIncomeId = UUID.randomUUID();
    private final UUID cashId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        journalService = new JournalService(journalEntryRepository, postingRepository, glAccountService);
        account = Account.open("NB100000001", UUID.randomUUID(), "Alice", AccountType.SAVINGS, "SGD", new BigDecimal("500.00"));
        org.mockito.Mockito.lenient().when(journalEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(glAccountService.system(SystemAccount.SETTLEMENT, "SGD")).thenReturn(gl(settlementId));
        org.mockito.Mockito.lenient().when(glAccountService.system(SystemAccount.FEE_INCOME, "SGD")).thenReturn(gl(feeIncomeId));
        org.mockito.Mockito.lenient().when(glAccountService.system(SystemAccount.EXTERNAL_FUNDING, "SGD")).thenReturn(gl(cashId));
    }

    private static GlAccount gl(UUID id) {
        GlAccount gl = org.mockito.Mockito.mock(GlAccount.class);
        org.mockito.Mockito.lenient().when(gl.getId()).thenReturn(id);
        return gl;
    }

    @SuppressWarnings("unchecked")
    private List<Posting> savedPostings() {
        ArgumentCaptor<List<Posting>> captor = ArgumentCaptor.forClass(List.class);
        verify(postingRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static BigDecimal total(List<Posting> postings, PostingDirection direction) {
        return postings.stream().filter(p -> p.getDirection() == direction).map(Posting::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void openingDeposit_debitsCash_creditsCustomer() {
        journalService.postOpeningDeposit(account, UUID.randomUUID(), new BigDecimal("1000.00"), "BANK-REF-1");

        List<Posting> postings = savedPostings();
        assertThat(postings).hasSize(2);
        assertThat(total(postings, PostingDirection.DEBIT)).isEqualByComparingTo("1000.00");
        assertThat(total(postings, PostingDirection.CREDIT)).isEqualByComparingTo("1000.00");
        assertThat(postings).anySatisfy(p -> {
            assertThat(p.getGlAccountId()).isEqualTo(cashId);
            assertThat(p.getDirection()).isEqualTo(PostingDirection.DEBIT);   // cash (asset) goes up
        });
        assertThat(postings).anySatisfy(p -> {
            assertThat(p.getCustomerAccountId()).isEqualTo(account.getId());
            assertThat(p.getDirection()).isEqualTo(PostingDirection.CREDIT);  // liability goes up
        });
    }

    @Test
    void openingDeposit_withoutAReferenceGetsADeterministicOne() {
        UUID depositId = UUID.randomUUID();
        journalService.postOpeningDeposit(account, depositId, BigDecimal.TEN, null);

        ArgumentCaptor<JournalEntry> journal = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(journal.capture());
        assertThat(journal.getValue().getExternalReference()).isEqualTo("NB-DEP-" + depositId);
        assertThat(journal.getValue().getJournalType()).isEqualTo(JournalType.OPENING_DEPOSIT);
    }

    @Test
    void transferDebit_withoutFee_isTwoBalancedPostings() {
        journalService.postTransferDebit(account, UUID.randomUUID(), new BigDecimal("100.00"), BigDecimal.ZERO);

        List<Posting> postings = savedPostings();
        assertThat(postings).hasSize(2);
        assertThat(total(postings, PostingDirection.DEBIT)).isEqualByComparingTo("100.00");
        assertThat(total(postings, PostingDirection.CREDIT)).isEqualByComparingTo("100.00");
    }

    @Test
    void transferDebit_withFee_splitsTheCreditBetweenSettlementAndFeeIncome() {
        journalService.postTransferDebit(account, UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("1.50"));

        List<Posting> postings = savedPostings();
        assertThat(postings).hasSize(3);
        assertThat(total(postings, PostingDirection.DEBIT)).isEqualByComparingTo("101.50");   // sender pays amount + fee
        assertThat(total(postings, PostingDirection.CREDIT)).isEqualByComparingTo("101.50");
        assertThat(postings).anySatisfy(p -> {
            assertThat(p.getGlAccountId()).isEqualTo(settlementId);
            assertThat(p.getAmount()).isEqualByComparingTo("100.00");
        });
        assertThat(postings).anySatisfy(p -> {
            assertThat(p.getGlAccountId()).isEqualTo(feeIncomeId);
            assertThat(p.getAmount()).isEqualByComparingTo("1.50");
        });
    }

    @Test
    void transferCredit_debitsSettlement_creditsReceiver() {
        journalService.postTransferCredit(account, UUID.randomUUID(), new BigDecimal("100.00"));

        List<Posting> postings = savedPostings();
        assertThat(postings).anySatisfy(p -> {
            assertThat(p.getGlAccountId()).isEqualTo(settlementId);
            assertThat(p.getDirection()).isEqualTo(PostingDirection.DEBIT);   // money leaves "in flight"
        });
        assertThat(postings).anySatisfy(p -> {
            assertThat(p.getCustomerAccountId()).isEqualTo(account.getId());
            assertThat(p.getDirection()).isEqualTo(PostingDirection.CREDIT);
        });
    }

    @Test
    void transferReversal_isTheExactMirrorOfTheOriginalDebit_includingTheFee() {
        UUID txId = UUID.randomUUID();
        JournalEntry original = JournalEntry.create(txId, JournalType.TRANSFER_DEBIT, "SGD", "Transfer debit", null, null);
        UUID originalId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(original, "id", originalId);
        when(journalEntryRepository.findByTransactionIdAndJournalType(txId, JournalType.TRANSFER_DEBIT))
                .thenReturn(Optional.of(original));
        when(postingRepository.findByJournalId(originalId)).thenReturn(List.of(
                Posting.customer(originalId, account.getId(), PostingDirection.DEBIT, new BigDecimal("101.50"), "SGD"),
                Posting.gl(originalId, settlementId, PostingDirection.CREDIT, new BigDecimal("100.00"), "SGD"),
                Posting.gl(originalId, feeIncomeId, PostingDirection.CREDIT, new BigDecimal("1.50"), "SGD")));

        JournalService.ReversalResult result = journalService.postTransferReversal(account, txId, new BigDecimal("100.00"));

        assertThat(result.principal()).isEqualByComparingTo("100.00");
        assertThat(result.fee()).isEqualByComparingTo("1.50");
        assertThat(result.total()).isEqualByComparingTo("101.50");

        List<Posting> mirror = savedPostings();
        assertThat(mirror).hasSize(3);
        assertThat(mirror).anySatisfy(p -> {
            assertThat(p.getCustomerAccountId()).isEqualTo(account.getId());
            assertThat(p.getDirection()).isEqualTo(PostingDirection.CREDIT);          // sender refunded in full
            assertThat(p.getAmount()).isEqualByComparingTo("101.50");
        });
        assertThat(total(mirror, PostingDirection.DEBIT)).isEqualByComparingTo(total(mirror, PostingDirection.CREDIT));

        ArgumentCaptor<JournalEntry> journal = ArgumentCaptor.forClass(JournalEntry.class);
        verify(journalEntryRepository).save(journal.capture());
        assertThat(journal.getValue().getJournalType()).isEqualTo(JournalType.TRANSFER_REVERSAL);
        assertThat(journal.getValue().getReversesJournalId()).isEqualTo(originalId);
    }

    @Test
    void transferReversal_forADebitThatPredatesTheJournal_fallsBackToLegacyClearing() {
        UUID txId = UUID.randomUUID();
        UUID legacyId = UUID.randomUUID();
        when(journalEntryRepository.findByTransactionIdAndJournalType(txId, JournalType.TRANSFER_DEBIT)).thenReturn(Optional.empty());
        when(glAccountService.system(SystemAccount.LEGACY_CLEARING, "SGD")).thenReturn(gl(legacyId));

        JournalService.ReversalResult result = journalService.postTransferReversal(account, txId, new BigDecimal("40.00"));

        assertThat(result.total()).isEqualByComparingTo("40.00");
        List<Posting> postings = savedPostings();
        assertThat(postings).anySatisfy(p -> assertThat(p.getGlAccountId()).isEqualTo(legacyId));
        assertThat(total(postings, PostingDirection.DEBIT)).isEqualByComparingTo(total(postings, PostingDirection.CREDIT));
    }

    @Test
    void transferReversal_refusesToRefundADifferentAccountThanWasDebited() {
        UUID txId = UUID.randomUUID();
        UUID originalId = UUID.randomUUID();
        JournalEntry original = JournalEntry.create(txId, JournalType.TRANSFER_DEBIT, "SGD", "Transfer debit", null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(original, "id", originalId);
        when(journalEntryRepository.findByTransactionIdAndJournalType(txId, JournalType.TRANSFER_DEBIT)).thenReturn(Optional.of(original));
        when(postingRepository.findByJournalId(originalId)).thenReturn(List.of(
                Posting.customer(originalId, UUID.randomUUID(), PostingDirection.DEBIT, BigDecimal.TEN, "SGD"),
                Posting.gl(originalId, settlementId, PostingDirection.CREDIT, BigDecimal.TEN, "SGD")));

        assertThatThrownBy(() -> journalService.postTransferReversal(account, txId, BigDecimal.TEN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match");
    }

    @Test
    void holdCapture_debitsCustomer_creditsCash() {
        journalService.postHoldCapture(account, UUID.randomUUID(), new BigDecimal("30.00"), "AUTH-1");

        List<Posting> postings = savedPostings();
        assertThat(postings).anySatisfy(p -> {
            assertThat(p.getCustomerAccountId()).isEqualTo(account.getId());
            assertThat(p.getDirection()).isEqualTo(PostingDirection.DEBIT);
        });
        assertThat(postings).anySatisfy(p -> {
            assertThat(p.getGlAccountId()).isEqualTo(cashId);
            assertThat(p.getDirection()).isEqualTo(PostingDirection.CREDIT);   // cash (asset) goes down
        });
    }

    @Test
    void requireBalanced_rejectsUnbalancedOrDegenerateJournals() {
        UUID a = UUID.randomUUID();
        assertThatThrownBy(() -> JournalService.requireBalanced(List.of(
                JournalService.Line.customer(a, PostingDirection.DEBIT, BigDecimal.TEN),
                JournalService.Line.gl(a, PostingDirection.CREDIT, new BigDecimal("9.99")))))
                .hasMessageContaining("Unbalanced");
        assertThatThrownBy(() -> JournalService.requireBalanced(List.of(
                JournalService.Line.customer(a, PostingDirection.DEBIT, BigDecimal.TEN))))
                .hasMessageContaining("at least two");
        assertThatThrownBy(() -> JournalService.requireBalanced(List.of(
                JournalService.Line.customer(a, PostingDirection.DEBIT, BigDecimal.ZERO),
                JournalService.Line.gl(a, PostingDirection.CREDIT, BigDecimal.ZERO))))
                .hasMessageContaining("positive");
    }

    @Test
    void anUnbalancedPlanIsNeverSavedAtAll() {
        // the guard runs before any repository write
        assertThatThrownBy(() -> journalService.postTransferDebit(account, UUID.randomUUID(), new BigDecimal("-1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(postingRepository, journalEntryRepository);
    }
}
