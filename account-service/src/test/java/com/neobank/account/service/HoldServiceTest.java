package com.neobank.account.service;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountType;
import com.neobank.account.domain.Hold;
import com.neobank.account.domain.HoldStatus;
import com.neobank.account.domain.JournalEntry;
import com.neobank.account.domain.JournalType;
import com.neobank.account.domain.LedgerEntry;
import com.neobank.account.domain.LedgerEntryType;
import com.neobank.account.exception.AccountNotFoundException;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.HoldRepository;
import com.neobank.account.repository.LedgerEntryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    @Mock private HoldRepository holdRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private JournalService journalService;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private HoldService holdService;
    private Account account;
    private final JournalEntry journal = JournalEntry.create(UUID.randomUUID(), JournalType.HOLD_CAPTURE, "SGD", "capture", null, null);

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        holdService = new HoldService(holdRepository, accountRepository, ledgerEntryRepository, journalService, meters,
                clock, Duration.ofDays(7), Duration.ofDays(30));
        account = Account.open("NB100000009", UUID.randomUUID(), "Alice", AccountType.SAVINGS, "SGD", new BigDecimal("500.00"));
        lenient().when(accountRepository.findByIdForUpdate(any())).thenReturn(Optional.of(account));
        lenient().when(holdRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(holdRepository.sumActive(any(), any())).thenReturn(BigDecimal.ZERO);
        lenient().when(journalService.postHoldCapture(any(), any(), any(), any())).thenReturn(journal);
    }

    private Hold activeHold(String amount) {
        return Hold.place(account.getId(), new BigDecimal(amount), "SGD", "AUTH-1", "card auth", NOW.plus(Duration.ofDays(1)));
    }

    // ---- place

    @Test
    void place_reservesMoney_andAppliesTheDefaultTtl() {
        Hold hold = holdService.place(account.getId(), new BigDecimal("120.00"), "AUTH-1", "card auth", null);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getAmount()).isEqualByComparingTo("120.00");
        assertThat(hold.getCurrency()).isEqualTo("SGD");
        assertThat(hold.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(meters.get("neobank.holds.placed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void place_isIdempotentPerReference_returningTheOriginalHold() {
        Hold existing = activeHold("120.00");
        when(holdRepository.findByAccountIdAndReference(account.getId(), "AUTH-1")).thenReturn(Optional.of(existing));

        Hold again = holdService.place(account.getId(), new BigDecimal("120.00"), "AUTH-1", null, null);

        assertThat(again).isSameAs(existing);
        verify(holdRepository, never()).save(any());
    }

    @Test
    void place_rejectsReusingAReferenceWithADifferentAmount() {
        when(holdRepository.findByAccountIdAndReference(account.getId(), "AUTH-1")).thenReturn(Optional.of(activeHold("120.00")));

        assertThatThrownBy(() -> holdService.place(account.getId(), new BigDecimal("999.00"), "AUTH-1", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("different amount");
    }

    @Test
    void place_cannotReserveMoreThanIsAvailable_countingExistingHolds() {
        when(holdRepository.sumActive(any(), any())).thenReturn(new BigDecimal("450.00"));   // 500 - 450 = 50 available

        assertThatThrownBy(() -> holdService.place(account.getId(), new BigDecimal("60.00"), "AUTH-2", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Insufficient available balance");
        verify(holdRepository, never()).save(any());
    }

    @Test
    void place_rejectsFrozenAccounts() {
        account.freeze();

        assertThatThrownBy(() -> holdService.place(account.getId(), BigDecimal.TEN, "AUTH-3", null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("FROZEN");
    }

    @Test
    void place_validatesInput() {
        UUID id = account.getId();
        assertThatThrownBy(() -> holdService.place(id, BigDecimal.ZERO, "R", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holdService.place(id, BigDecimal.TEN, " ", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> holdService.place(id, BigDecimal.TEN, "R", null, Duration.ofDays(31)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ttl");
        assertThatThrownBy(() -> holdService.place(id, BigDecimal.TEN, "R", null, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void place_unknownAccount() {
        when(accountRepository.findByIdForUpdate(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.place(UUID.randomUUID(), BigDecimal.TEN, "R", null, null))
                .isInstanceOf(AccountNotFoundException.class);
    }

    // ---- release

    @Test
    void release_freesTheHold_andIsIdempotent() {
        Hold hold = activeHold("100.00");
        when(holdRepository.findByIdAndAccountId(any(), any())).thenReturn(Optional.of(hold));

        assertThat(holdService.release(account.getId(), UUID.randomUUID()).getStatus()).isEqualTo(HoldStatus.RELEASED);
        assertThat(holdService.release(account.getId(), UUID.randomUUID()).getStatus()).isEqualTo(HoldStatus.RELEASED);
    }

    @Test
    void release_cannotUndoACapture() {
        Hold hold = activeHold("100.00");
        hold.capture(new BigDecimal("100.00"), UUID.randomUUID());
        when(holdRepository.findByIdAndAccountId(any(), any())).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> holdService.release(account.getId(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CAPTURED");
    }

    @Test
    void unknownHoldIsNotFound() {
        when(holdRepository.findByIdAndAccountId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.release(account.getId(), UUID.randomUUID())).isInstanceOf(NoSuchElementException.class);
    }

    // ---- capture

    @Test
    void capture_debitsTheAccount_postsTheJournal_andWritesAStatementLine() {
        Hold hold = activeHold("100.00");
        when(holdRepository.findByIdAndAccountId(any(), any())).thenReturn(Optional.of(hold));

        Hold captured = holdService.capture(account.getId(), UUID.randomUUID(), null);

        assertThat(captured.getStatus()).isEqualTo(HoldStatus.CAPTURED);
        assertThat(captured.getCapturedAmount()).isEqualByComparingTo("100.00");
        assertThat(account.getBalance()).isEqualByComparingTo("400.00");
        verify(journalService).postHoldCapture(any(), any(), org.mockito.ArgumentMatchers.eq(new BigDecimal("100.00")),
                org.mockito.ArgumentMatchers.eq("AUTH-1"));
        ArgumentCaptor<LedgerEntry> ledger = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(ledger.capture());
        assertThat(ledger.getValue().getEntryType()).isEqualTo(LedgerEntryType.DEBIT);
        assertThat(ledger.getValue().getBalanceAfter()).isEqualByComparingTo("400.00");
    }

    @Test
    void capture_canBePartial_theRemainderIsSimplyNotTaken() {
        Hold hold = activeHold("100.00");
        when(holdRepository.findByIdAndAccountId(any(), any())).thenReturn(Optional.of(hold));

        Hold captured = holdService.capture(account.getId(), UUID.randomUUID(), new BigDecimal("60.00"));

        assertThat(captured.getCapturedAmount()).isEqualByComparingTo("60.00");
        assertThat(account.getBalance()).isEqualByComparingTo("440.00");
    }

    @Test
    void capture_cannotExceedTheHeldAmount() {
        when(holdRepository.findByIdAndAccountId(any(), any())).thenReturn(Optional.of(activeHold("100.00")));

        assertThatThrownBy(() -> holdService.capture(account.getId(), UUID.randomUUID(), new BigDecimal("100.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    void capture_isRejectedOnceTheHoldHasLapsed() {
        Hold lapsed = Hold.place(account.getId(), new BigDecimal("100.00"), "SGD", "AUTH-OLD", null, NOW.minusSeconds(1));
        when(holdRepository.findByIdAndAccountId(any(), any())).thenReturn(Optional.of(lapsed));

        assertThatThrownBy(() -> holdService.capture(account.getId(), UUID.randomUUID(), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expired");
        verify(journalService, never()).postHoldCapture(any(), any(), any(), any());
    }

    @Test
    void capture_isIdempotent() {
        Hold hold = activeHold("100.00");
        hold.capture(new BigDecimal("100.00"), UUID.randomUUID());
        when(holdRepository.findByIdAndAccountId(any(), any())).thenReturn(Optional.of(hold));

        Hold again = holdService.capture(account.getId(), UUID.randomUUID(), null);

        assertThat(again).isSameAs(hold);
        assertThat(account.getBalance()).isEqualByComparingTo("500.00");   // not debited a second time
        verify(journalService, never()).postHoldCapture(any(), any(), any(), any());
    }

    // ---- availability and expiry

    @Test
    void heldAmountIsTheSumOfActiveHolds() {
        when(holdRepository.sumActive(account.getId(), NOW)).thenReturn(new BigDecimal("75.00"));

        assertThat(holdService.heldAmount(account.getId())).isEqualByComparingTo("75.00");
    }

    @Test
    void expireDueHolds_countsWhatItExpired() {
        when(holdRepository.expireDue(NOW)).thenReturn(3);

        assertThat(holdService.expireDueHolds()).isEqualTo(3);
        assertThat(meters.get("neobank.holds.expired").counter().count()).isEqualTo(3.0);
    }
}
