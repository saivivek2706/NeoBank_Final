package com.neobank.transaction.repository;

import com.neobank.transaction.domain.Transaction;
import com.neobank.transaction.domain.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    List<Transaction> findByFromAccountIdOrToAccountIdOrderByCreatedAtDesc(UUID fromAccountId, UUID toAccountId);

    /** Idempotency lookup: has this caller already submitted a transfer with this key? */
    Optional<Transaction> findByRequestedByAndIdempotencyKey(UUID requestedBy, String idempotencyKey);

    /**
     * Sum of money already sent out of this account since `since`, excluding
     * transfers that failed outright (they never actually moved money).
     * Backs the rolling-24h velocity cap - see VelocityLimitService.
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
            "WHERE t.fromAccountId = :accountId AND t.createdAt >= :since AND t.status <> :excludedStatus")
    BigDecimal sumAmountSentSince(@Param("accountId") UUID accountId, @Param("since") Instant since,
                                   @Param("excludedStatus") TransactionStatus excludedStatus);

    /**
     * Count of transfer ATTEMPTS (successful or not) initiated from this
     * account since `since` - deliberately counts failed attempts too, since
     * this is meant to catch rapid-fire attack patterns, not just successful
     * transfers.
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.fromAccountId = :accountId AND t.createdAt >= :since")
    long countAttemptsSince(@Param("accountId") UUID accountId, @Param("since") Instant since);
}
