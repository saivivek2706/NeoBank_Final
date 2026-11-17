package com.neobank.compliance.repository;

import com.neobank.compliance.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /** Retention for the idempotent-consumer dedup table - see OutboxCleanupJob for why the window must exceed Kafka's topic retention. */
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :before")
    int deleteProcessedBefore(@Param("before") Instant before);
}
