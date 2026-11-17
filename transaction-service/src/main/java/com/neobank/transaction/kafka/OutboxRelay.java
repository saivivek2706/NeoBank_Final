package com.neobank.transaction.kafka;

import com.neobank.transaction.domain.OutboxEvent;
import com.neobank.transaction.domain.OutboxStatus;
import com.neobank.transaction.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relay() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        for (OutboxEvent event : pending) {
            try {
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
