package com.neobank.notification.kafka;

import com.neobank.notification.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Retention for the idempotent-consumer dedup table (processed_events). The window MUST be longer than
 * Kafka's topic retention (7 days by default) - once a row is purged, a redelivery of that event would no
 * longer be recognised as a duplicate. Default 14 days. The DELETE is idempotent, so replicas running it
 * concurrently is harmless and no distributed lock is needed here.
 */
@Component
@Slf4j
public class ProcessedEventCleanupJob {

    private final ProcessedEventRepository processedEventRepository;
    private final Duration retention;

    public ProcessedEventCleanupJob(ProcessedEventRepository processedEventRepository,
                                    @Value("${maintenance.processed-events.retention:P14D}") Duration retention) {
        this.processedEventRepository = processedEventRepository;
        this.retention = retention;
    }

    @Scheduled(cron = "${maintenance.cleanup.cron:0 30 3 * * *}")
    @Transactional
    public void purge() {
        int deleted = processedEventRepository.deleteProcessedBefore(Instant.now().minus(retention));
        log.info("Housekeeping purged {} processed_events rows older than {}", deleted, retention);
    }
}
