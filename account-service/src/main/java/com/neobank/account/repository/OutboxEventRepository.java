package com.neobank.account.repository;

import com.neobank.account.domain.OutboxEvent;
import com.neobank.account.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    /** Backs the neobank.outbox.pending / .dead gauges (relay lag + stuck events). */
    long countByStatus(OutboxStatus status);

    /** Retention: already-published rows are only useful for debugging, so old ones are purged. DEAD rows are never auto-deleted. */
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :before")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status, @Param("before") Instant before);
}
