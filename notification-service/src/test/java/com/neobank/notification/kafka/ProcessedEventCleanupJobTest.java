package com.neobank.notification.kafka;

import com.neobank.notification.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProcessedEventCleanupJobTest {

    @Mock private ProcessedEventRepository repository;

    @Test
    void purge_deletesRowsOlderThanTheRetentionWindow() {
        new ProcessedEventCleanupJob(repository, Duration.ofDays(14)).purge();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteProcessedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(
                Instant.now().minus(Duration.ofDays(14)).minusSeconds(5),
                Instant.now().minus(Duration.ofDays(14)).plusSeconds(5));
    }
}
