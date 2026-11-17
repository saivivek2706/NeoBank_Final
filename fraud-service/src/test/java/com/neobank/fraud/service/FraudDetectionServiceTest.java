package com.neobank.fraud.service;

import com.neobank.fraud.domain.FraudAlert;
import com.neobank.fraud.domain.FraudRuleType;
import com.neobank.fraud.kafka.event.TransactionEvent;
import com.neobank.fraud.repository.FraudAlertRepository;
import com.neobank.fraud.repository.ProcessedEventRepository;
import com.neobank.fraud.websocket.FraudAlertNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock private FraudAlertRepository fraudAlertRepository;
    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private FraudAlertNotifier notifier;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOperations;

    private FraudDetectionService fraudDetectionService;

    @BeforeEach
    void setUp() {
        fraudDetectionService = new FraudDetectionService(fraudAlertRepository, processedEventRepository,
                notifier, redisTemplate);
        ReflectionTestUtils.setField(fraudDetectionService, "largeAmountThreshold", new BigDecimal("10000"));
        ReflectionTestUtils.setField(fraudDetectionService, "highFrequencyCount", 5);
        ReflectionTestUtils.setField(fraudDetectionService, "highFrequencyWindowSeconds", 60L);

        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        // Keep the frequency rule quiet by default in tests that only care about the amount rule.
        lenient().when(zSetOperations.range(any(), anyLong(), anyLong())).thenReturn(Set.of("1"));
    }

    @Test
    void screen_flagsLargeAmount_whenOverThreshold() {
        TransactionEvent event = event(new BigDecimal("15000.00"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        fraudDetectionService.screen(event);

        verify(fraudAlertRepository).save(argThat(a -> a.getRuleTriggered() == FraudRuleType.LARGE_AMOUNT));
        verify(notifier).broadcast(any(FraudAlert.class));
    }

    @Test
    void screen_doesNotFlag_whenAmountUnderThreshold() {
        TransactionEvent event = event(new BigDecimal("500.00"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);

        fraudDetectionService.screen(event);

        verify(fraudAlertRepository, never()).save(argThat(a -> a.getRuleTriggered() == FraudRuleType.LARGE_AMOUNT));
    }

    @Test
    void screen_flagsHighFrequency_whenOverCountThreshold() {
        TransactionEvent event = event(new BigDecimal("50.00"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(false);
        // Simulate 6 recent debits in the sliding window - over the threshold of 5.
        when(zSetOperations.range(any(), anyLong(), anyLong()))
                .thenReturn(Set.of("1", "2", "3", "4", "5", "6"));

        fraudDetectionService.screen(event);

        verify(fraudAlertRepository).save(argThat(a -> a.getRuleTriggered() == FraudRuleType.HIGH_FREQUENCY));
    }

    @Test
    void screen_isIdempotent_skipsAlreadyProcessedEvent() {
        TransactionEvent event = event(new BigDecimal("50000.00"));
        when(processedEventRepository.existsById(event.eventId())).thenReturn(true);

        fraudDetectionService.screen(event);

        verifyNoInteractions(fraudAlertRepository);
        verifyNoInteractions(notifier);
    }

    private TransactionEvent event(BigDecimal amount) {
        return new TransactionEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                amount, "SGD", null, java.time.Instant.now());
    }
}
