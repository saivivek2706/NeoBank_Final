package com.neobank.account.repository;

import com.neobank.account.domain.JournalEntry;
import com.neobank.account.domain.JournalType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByTransactionIdAndJournalType(UUID transactionId, JournalType journalType);

    List<JournalEntry> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    /**
     * Transfers whose sender was debited more than `before` ago but which never reached a terminal ledger state
     * (neither credited to the receiver nor reversed back to the sender): money is stuck in SETTLEMENT.
     */
    @Query("""
            SELECT d FROM JournalEntry d
            WHERE d.journalType = com.neobank.account.domain.JournalType.TRANSFER_DEBIT
              AND d.createdAt < :before
              AND NOT EXISTS (
                    SELECT 1 FROM JournalEntry x
                    WHERE x.transactionId = d.transactionId
                      AND x.journalType IN (com.neobank.account.domain.JournalType.TRANSFER_CREDIT,
                                            com.neobank.account.domain.JournalType.TRANSFER_REVERSAL))
            ORDER BY d.createdAt
            """)
    List<JournalEntry> findStuckInSettlement(@Param("before") Instant before);
}
