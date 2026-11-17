package com.neobank.account.service;

import com.neobank.account.domain.Account;
import com.neobank.account.domain.AccountStatus;
import com.neobank.account.domain.Hold;
import com.neobank.account.domain.HoldStatus;
import com.neobank.account.domain.JournalEntry;
import com.neobank.account.domain.LedgerEntry;
import com.neobank.account.domain.LedgerEntryType;
import com.neobank.account.exception.AccountNotFoundException;
import com.neobank.account.repository.AccountRepository;
import com.neobank.account.repository.HoldRepository;
import com.neobank.account.repository.LedgerEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Authorization holds: reserve part of an account's balance without moving money.
 *
 *   available balance = balance - sum(ACTIVE, unexpired holds)
 *
 * Transfers can only spend the AVAILABLE balance (see AccountService). A hold is later captured (the real
 * debit, posted to the journal), released, or simply lapses. Every operation takes the account row lock first,
 * the same lock the transfer saga takes, so a hold and a transfer can never both spend the same money.
 *
 * Creation is idempotent per (account, reference): a caller retrying with the same reference gets the original hold.
 */
@Service
public class HoldService {

    private final HoldRepository holdRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final JournalService journalService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Duration defaultTtl;
    private final Duration maxTtl;

    public HoldService(HoldRepository holdRepository,
                       AccountRepository accountRepository,
                       LedgerEntryRepository ledgerEntryRepository,
                       JournalService journalService,
                       MeterRegistry meterRegistry,
                       Clock clock,
                       @Value("${holds.default-ttl:PT168H}") Duration defaultTtl,
                       @Value("${holds.max-ttl:P30D}") Duration maxTtl) {
        this.holdRepository = holdRepository;
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.journalService = journalService;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.defaultTtl = defaultTtl;
        this.maxTtl = maxTtl;
    }

    /** Total currently reserved on the account. */
    @Transactional(readOnly = true)
    public BigDecimal heldAmount(UUID accountId) {
        return holdRepository.sumActive(accountId, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<Hold> activeHolds(UUID accountId) {
        return holdRepository.findActive(accountId, clock.instant());
    }

    @Transactional
    public Hold place(UUID accountId, BigDecimal amount, String reference, String reason, Duration ttl) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Hold amount must be positive");
        }
        if (reference == null || reference.isBlank() || reference.length() > 100) {
            throw new IllegalArgumentException("Hold reference is required (max 100 characters)");
        }
        Duration effectiveTtl = ttl == null ? defaultTtl : ttl;
        if (effectiveTtl.isNegative() || effectiveTtl.isZero() || effectiveTtl.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException("Hold ttl must be positive and at most " + maxTtl);
        }

        Account account = lock(accountId);
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new IllegalStateException("Account is " + account.getStatus() + ", cannot place a hold");
        }

        String ref = reference.trim();
        var existing = holdRepository.findByAccountIdAndReference(accountId, ref);
        if (existing.isPresent()) {
            if (existing.get().getAmount().compareTo(amount) != 0) {
                throw new IllegalStateException("Hold reference " + ref + " was already used with a different amount");
            }
            return existing.get();
        }

        Instant now = clock.instant();
        BigDecimal available = account.getBalance().subtract(holdRepository.sumActive(accountId, now));
        if (available.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient available balance to place this hold");
        }
        Hold hold = holdRepository.save(Hold.place(accountId, amount, account.getCurrency(), ref, reason,
                now.plus(effectiveTtl)));
        meterRegistry.counter("neobank.holds.placed", "currency", account.getCurrency()).increment();
        return hold;
    }

    @Transactional
    public Hold release(UUID accountId, UUID holdId) {
        lock(accountId);
        Hold hold = find(accountId, holdId);
        if (hold.getStatus() == HoldStatus.RELEASED) {
            return hold;   // idempotent
        }
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new IllegalStateException("Hold is " + hold.getStatus() + " and cannot be released");
        }
        hold.release();
        return holdRepository.save(hold);
    }

    /**
     * Turns a hold into a real debit. `amount` may be less than the hold (a partial capture - the remainder is
     * released), never more. Posts the money movement to the journal and writes the statement line.
     */
    @Transactional
    public Hold capture(UUID accountId, UUID holdId, BigDecimal amount) {
        Account account = lock(accountId);
        Hold hold = find(accountId, holdId);
        if (hold.getStatus() == HoldStatus.CAPTURED) {
            return hold;   // idempotent
        }
        Instant now = clock.instant();
        if (!hold.isActiveAt(now)) {
            throw new IllegalStateException("Hold is " + (hold.getStatus() == HoldStatus.ACTIVE ? "expired" : hold.getStatus())
                    + " and cannot be captured");
        }
        BigDecimal toCapture = amount == null ? hold.getAmount() : amount;
        if (toCapture.signum() <= 0 || toCapture.compareTo(hold.getAmount()) > 0) {
            throw new IllegalArgumentException("Capture amount must be positive and at most the held amount " + hold.getAmount());
        }

        account.debit(toCapture);
        accountRepository.save(account);
        JournalEntry journal = journalService.postHoldCapture(account, hold.getId(), toCapture, hold.getReference());
        ledgerEntryRepository.save(LedgerEntry.of(account.getId(), hold.getId(), LedgerEntryType.DEBIT, toCapture,
                account.getBalance(), "Captured hold " + hold.getReference(), journal.getId()));

        hold.capture(toCapture, journal.getId());
        meterRegistry.counter("neobank.holds.captured", "currency", account.getCurrency()).increment();
        return holdRepository.save(hold);
    }

    /** Housekeeping (scheduled): marks lapsed holds EXPIRED. They already stopped counting against the balance. */
    @Transactional
    public int expireDueHolds() {
        int expired = holdRepository.expireDue(clock.instant());
        if (expired > 0) {
            meterRegistry.counter("neobank.holds.expired").increment(expired);
        }
        return expired;
    }

    private Account lock(UUID accountId) {
        return accountRepository.findByIdForUpdate(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private Hold find(UUID accountId, UUID holdId) {
        return holdRepository.findByIdAndAccountId(holdId, accountId)
                .orElseThrow(() -> new NoSuchElementException("Hold " + holdId + " not found on account " + accountId));
    }
}
