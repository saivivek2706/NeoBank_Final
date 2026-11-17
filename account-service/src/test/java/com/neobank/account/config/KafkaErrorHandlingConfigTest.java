package com.neobank.account.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaErrorHandlingConfigTest {

    @Test
    void poisonRecordIsRoutedToTheDltOfItsTopic_andCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("account.debit.completed", 2, 41L, "key", "{bad json");

        TopicPartition destination = KafkaErrorHandlingConfig.deadLetterDestination(record, registry);

        assertThat(destination.topic()).isEqualTo("account.debit.completed.DLT");
        assertThat(destination.partition()).isNegative();   // producer chooses - DLT may have fewer partitions
        assertThat(registry.get("neobank.kafka.dead.letters").tag("topic", "account.debit.completed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorHandlerBeanIsBuilt() {
        DefaultErrorHandler handler = new KafkaErrorHandlingConfig()
                .kafkaErrorHandler(mock(KafkaTemplate.class), new SimpleMeterRegistry());

        assertThat(handler).isNotNull();
    }
}
