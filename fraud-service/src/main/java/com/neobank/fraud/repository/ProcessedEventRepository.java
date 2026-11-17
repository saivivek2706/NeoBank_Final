package com.neobank.fraud.repository;

import com.neobank.fraud.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /** Retention for the idempotent-consumer dedup table - see ProcessedEventCleanupJob. */
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :before")
    int deleteProcessedBefore(@Param("before") Instant before);
}
