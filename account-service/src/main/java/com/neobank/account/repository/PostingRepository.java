package com.neobank.account.repository;

import com.neobank.account.domain.Posting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    List<Posting> findByJournalId(UUID journalId);

    /** A customer account's balance derived purely from postings: credits minus debits (it is a liability of the bank). */
    @Query("SELECT COALESCE(SUM(CASE WHEN p.direction = com.neobank.account.domain.PostingDirection.CREDIT "
            + "THEN p.amount ELSE -p.amount END), 0) FROM Posting p WHERE p.customerAccountId = :accountId")
    BigDecimal customerBalance(@Param("accountId") UUID accountId);

    /** Postings-derived balance of every customer account in one pass: [accountId, balance]. */
    @Query("SELECT p.customerAccountId, SUM(CASE WHEN p.direction = com.neobank.account.domain.PostingDirection.CREDIT "
            + "THEN p.amount ELSE -p.amount END) FROM Posting p WHERE p.customerAccountId IS NOT NULL "
            + "GROUP BY p.customerAccountId")
    List<Object[]> customerBalances();

    /** [glAccountId, direction, total] for every system account (small result: accounts x 2). */
    @Query("SELECT p.glAccountId, p.direction, SUM(p.amount) FROM Posting p "
            + "WHERE p.glAccountId IS NOT NULL GROUP BY p.glAccountId, p.direction")
    List<Object[]> glTotals();

    /** [currency, direction, total] over ALL postings: per currency, DEBIT and CREDIT totals must be equal (the trial balance). */
    @Query("SELECT p.currency, p.direction, SUM(p.amount) FROM Posting p GROUP BY p.currency, p.direction")
    List<Object[]> totalsByCurrency();

    /** Journals whose debits and credits differ. Must always be empty - the DB trigger is the first line of defence. */
    @Query("SELECT p.journalId FROM Posting p GROUP BY p.journalId HAVING "
            + "SUM(CASE WHEN p.direction = com.neobank.account.domain.PostingDirection.DEBIT THEN p.amount ELSE -p.amount END) <> 0")
    List<UUID> findUnbalancedJournals();

    /**
     * Movements of money in/out of the bank on a given day: postings against the EXTERNAL_FUNDING account.
     * Returns [externalReference, journalId, direction, amount, currency].
     */
    @Query("SELECT j.externalReference, j.id, p.direction, p.amount, p.currency "
            + "FROM Posting p, JournalEntry j, GlAccount g "
            + "WHERE p.journalId = j.id AND p.glAccountId = g.id AND g.code = :code AND g.currency = :currency "
            + "AND p.createdAt >= :from AND p.createdAt < :to")
    List<Object[]> externalMovements(@Param("code") String code, @Param("currency") String currency,
                                     @Param("from") Instant from, @Param("to") Instant to);
}
