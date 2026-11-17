package com.neobank.compliance.metrics;

import com.neobank.compliance.domain.OutboxStatus;
import com.neobank.compliance.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Outbox health as gauges: neobank_outbox_pending growing means the relay (or Kafka)
 * is falling behind; neobank_outbox_dead above zero means events exhausted their retries
 * and need a human - the alert condition for the outbox pattern.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxEventRepository repository) {
        Gauge.builder("neobank.outbox.pending", repository, r -> r.countByStatus(OutboxStatus.PENDING))
                .description("Outbox events waiting to be relayed to Kafka").register(registry);
        Gauge.builder("neobank.outbox.dead", repository, r -> r.countByStatus(OutboxStatus.DEAD))
                .description("Outbox events that exhausted their retries").register(registry);
    }
}
