package com.neobank.account.kafka;

import com.neobank.account.domain.OutboxEvent;
import com.neobank.account.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polls the outbox table every second and relays pending events to Kafka.
 * This is the "relay" half of the outbox pattern (see OutboxEvent for the why).
 *
 * In a higher-throughput production system you'd swap this polling loop for
 * Debezium CDC tailing the Postgres WAL directly - zero polling delay and no
 * load on the table - but the polling approach is simpler to run locally and
 * demonstrates the same guarantee.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outboxEventRepository
                .findTop100ByStatusOrderByCreatedAtAsc(com.neobank.account.domain.OutboxStatus.PENDING);

        for (OutboxEvent event : pending) {
            try {
                // Key by aggregateId (the accountId) so all events for one account
                // land on the same partition and are processed in order.
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload()).get();
                event.markSent();
                log.info("Relayed outbox event {} ({}) to topic {}", event.getId(), event.getEventType(), event.getTopic());
            } catch (Exception ex) {
                event.markFailed();
                log.error("Failed to relay outbox event {}, attempt {}", event.getId(), event.getAttempts(), ex);
            }
            outboxEventRepository.save(event);
        }
    }
}
