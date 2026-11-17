package com.neobank.account.repository;

import com.neobank.account.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Page<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    /**
     * Recomputes what this account's balance SHOULD be, purely from the
     * ledger (credits minus debits), independent of whatever
     * Account.balance currently says. Used by LedgerReconciliationService
     * and the /{id}/reconcile endpoint to catch any drift between the
     * cached balance and the append-only source of truth.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN e.entryType = com.neobank.account.domain.LedgerEntryType.CREDIT THEN e.amount ELSE -e.amount END), 0) " +
            "FROM LedgerEntry e WHERE e.accountId = :accountId")
    BigDecimal computeBalanceFromLedger(@Param("accountId") UUID accountId);
}
