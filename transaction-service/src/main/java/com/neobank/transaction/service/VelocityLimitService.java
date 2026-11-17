package com.neobank.transaction.service;

import com.neobank.transaction.domain.TransactionStatus;
import com.neobank.transaction.exception.VelocityLimitExceededException;
import com.neobank.transaction.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Bounds both HOW MUCH and HOW OFTEN a single account can send, independent
 * of the per-transfer step-up check. A single very large transfer AND many
 * small transfers in quick succession are different attack/fraud shapes -
 * this covers the second one (and a slow bleed that never crosses the
 * per-transfer step-up threshold).
 *
 * Implemented as a DB aggregate query over existing transaction rows rather
 * than a separate Redis counter - the account being debited is already
 * pessimistically locked for the duration of a transfer in account-service,
 * so there's no separate race to worry about here, and it stays consistent
 * with the saga's source of truth (the transactions table) instead of a
 * second, potentially-diverging counter.
 */
@Service
public class VelocityLimitService {

    private final TransactionRepository transactionRepository;
    private final BigDecimal maxAmountPer24h;
    private final long maxAttemptsPerHour;

    // Explicit constructor - see TransferService for why @Value fields aren't
    // used with @RequiredArgsConstructor here: it would silently leave these
    // null/zero in any manually-constructed instance (e.g. a unit test),
    // which would make every single transfer either always or never trip
    // the velocity limit depending on which way the null blows up.
    public VelocityLimitService(TransactionRepository transactionRepository,
                                 @Value("${transaction.velocity.max-amount-per-24h}") BigDecimal maxAmountPer24h,
                                 @Value("${transaction.velocity.max-attempts-per-hour}") long maxAttemptsPerHour) {
        this.transactionRepository = transactionRepository;
        this.maxAmountPer24h = maxAmountPer24h;
        this.maxAttemptsPerHour = maxAttemptsPerHour;
    }

    public void checkOrThrow(UUID fromAccountId, BigDecimal newTransferAmount) {
        // Simplification: amounts are summed as raw numbers regardless of
        // currency. A real multi-currency deployment would need to convert
        // to a common unit (or track limits per-currency) before comparing.
        Instant oneHourAgo = Instant.now().minus(Duration.ofHours(1));
        long recentAttempts = transactionRepository.countAttemptsSince(fromAccountId, oneHourAgo);
        if (recentAttempts >= maxAttemptsPerHour) {
            throw new VelocityLimitExceededException(
                    "Too many transfer attempts from this account in the last hour. Please try again later.");
        }

        Instant oneDayAgo = Instant.now().minus(Duration.ofHours(24));
        BigDecimal sentLast24h = transactionRepository.sumAmountSentSince(fromAccountId, oneDayAgo, TransactionStatus.FAILED);
        if (sentLast24h.add(newTransferAmount).compareTo(maxAmountPer24h) > 0) {
            throw new VelocityLimitExceededException(
                    "This transfer would exceed the account's 24-hour transfer limit.");
        }
    }
}
