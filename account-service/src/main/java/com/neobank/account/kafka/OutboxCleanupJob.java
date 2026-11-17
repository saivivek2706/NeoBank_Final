package com.neobank.account.kafka;

import com.neobank.account.domain.OutboxStatus;
import com.neobank.account.repository.OutboxEventRepository;
import com.neobank.account.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Housekeeping so the two bookkeeping tables don't grow forever:
 *
 *  - outbox_events: SENT rows older than the retention window are deleted. DEAD rows are kept
 *    on purpose - they are the evidence of an event that never reached Kafka.
 *  - processed_events: the idempotent-consumer dedup table. The retention MUST be longer than
 *    Kafka's topic retention (7 days by default): once a row is purged, a redelivery of the same
 *    event would no longer be recognised as a duplicate. Default is 14 days.
 *
 * Runs on one replica per tick (ShedLock).
 */
@Component
@Slf4j
public class OutboxCleanupJob {

    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final Duration outboxRetention;
    private final Duration processedEventsRetention;

    public OutboxCleanupJob(OutboxEventRepository outboxEventRepository,
                            ProcessedEventRepository processedEventRepository,
                            @Value("${maintenance.outbox.retention:PT72H}") Duration outboxRetention,
                            @Value("${maintenance.processed-events.retention:P14D}") Duration processedEventsRetention) {
        this.outboxEventRepository = outboxEventRepository;
        this.processedEventRepository = processedEventRepository;
        this.outboxRetention = outboxRetention;
        this.processedEventsRetention = processedEventsRetention;
    }

    @Scheduled(cron = "${maintenance.cleanup.cron:0 15 3 * * *}")
    @SchedulerLock(name = "account-service-cleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void purge() {
        Instant now = Instant.now();
        int outbox = outboxEventRepository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, now.minus(outboxRetention));
        int processed = processedEventRepository.deleteProcessedBefore(now.minus(processedEventsRetention));
        log.info("Housekeeping purged {} sent outbox rows and {} processed_events rows", outbox, processed);
    }
}
