package com.neobank.notification.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Poison-message protection for every @KafkaListener in this service.
 *
 * Without this, Spring Kafka's default is to retry a failing record a few times back-to-back and then
 * LOG AND DROP it - for a bank, a silently lost saga event is the worst possible failure mode. Instead:
 *
 *   1. Retry with exponential backoff (500ms, 1s, 2s, 4s) - rides out transient failures such as a
 *      database failover or an optimistic-lock conflict.
 *   2. If it still fails (or it is unparseable and retrying can never help), publish the ORIGINAL record
 *      to "<topic>.DLT" with the exception stack trace in headers, then move on so one bad message
 *      cannot block the partition behind it.
 *   3. neobank_kafka_dead_letters_total{topic} is incremented - alert on any non-zero rate.
 *
 * Replaying dead letters is documented in docs/runbook.md.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    static final String DLT_SUFFIX = ".DLT";

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> deadLetterDestination(record, meterRegistry));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Retrying cannot fix a malformed payload or an invalid argument - dead-letter immediately.
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }

    /** Where a record that exhausted its retries goes; also counts it for the dead-letter alert. */
    static TopicPartition deadLetterDestination(ConsumerRecord<?, ?> record, MeterRegistry meterRegistry) {
        meterRegistry.counter("neobank.kafka.dead.letters", "topic", record.topic()).increment();
        // Partition -1 = let the producer pick: the DLT may have fewer partitions than the source topic.
        return new TopicPartition(record.topic() + DLT_SUFFIX, -1);
    }
}
