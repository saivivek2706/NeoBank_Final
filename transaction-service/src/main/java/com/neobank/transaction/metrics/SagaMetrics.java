package com.neobank.transaction.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Business metrics for the transfer saga, scraped by Prometheus and charted on
 * the "NeoBank Saga" Grafana dashboard. Prometheus names:
 *
 *   neobank_transfers_started_total
 *   neobank_transfers_completed_total
 *   neobank_transfers_failed_total{stage="debit|credit"}
 *   neobank_transfers_compensated_total
 *   neobank_transfers_idempotent_replays_total
 *   neobank_transfers_saga_duration_seconds_{bucket,count,sum}{outcome="completed|failed"}
 */
@Component
public class SagaMetrics {

    private final MeterRegistry registry;
    private final Counter started;
    private final Counter completed;
    private final Counter compensated;
    private final Counter idempotentReplays;

    public SagaMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.started = Counter.builder("neobank.transfers.started")
                .description("Transfers accepted and handed to the saga").register(registry);
        this.completed = Counter.builder("neobank.transfers.completed")
                .description("Transfers that reached COMPLETED").register(registry);
        this.compensated = Counter.builder("neobank.transfers.compensated")
                .description("Transfers whose debit had to be reversed because the credit failed").register(registry);
        this.idempotentReplays = Counter.builder("neobank.transfers.idempotent.replays")
                .description("Duplicate POST /transfer requests answered from an existing transaction").register(registry);
    }

    public void started() {
        started.increment();
    }

    public void completed(Instant createdAt) {
        completed.increment();
        recordDuration("completed", createdAt);
    }

    /** @param stage which saga step failed: "debit" (nothing to undo) or "credit" (debit is compensated) */
    public void failed(String stage, Instant createdAt) {
        Counter.builder("neobank.transfers.failed")
                .description("Transfers that ended FAILED, by the saga stage that failed")
                .tag("stage", stage).register(registry).increment();
        recordDuration("failed", createdAt);
    }

    public void compensated() {
        compensated.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    private void recordDuration(String outcome, Instant createdAt) {
        if (createdAt == null) {
            return;
        }
        Timer.builder("neobank.transfers.saga.duration")
                .description("Time from transfer initiation to its terminal state")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.between(createdAt, Instant.now()));
    }
}
