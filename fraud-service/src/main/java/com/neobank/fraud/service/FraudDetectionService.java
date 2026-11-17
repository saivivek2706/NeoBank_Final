package com.neobank.fraud.service;

import com.neobank.fraud.domain.FraudAlert;
import com.neobank.fraud.domain.FraudRuleType;
import com.neobank.fraud.domain.FraudSeverity;
import com.neobank.fraud.domain.ProcessedEvent;
import com.neobank.fraud.kafka.event.TransactionEvent;
import com.neobank.fraud.repository.FraudAlertRepository;
import com.neobank.fraud.repository.ProcessedEventRepository;
import com.neobank.fraud.websocket.FraudAlertNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Two intentionally simple, explainable rules - real fraud systems layer in
 * ML scoring models, device fingerprinting, geolocation velocity checks etc,
 * but the architecture (screen every completed debit, flag + alert live,
 * never block the money movement itself) is the same shape as production.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudDetectionService {

    private final FraudAlertRepository fraudAlertRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final FraudAlertNotifier notifier;
    private final StringRedisTemplate redisTemplate;

    @Value("${fraud.rules.large-amount-threshold:10000}")
    private BigDecimal largeAmountThreshold;

    @Value("${fraud.rules.high-frequency-count:5}")
    private int highFrequencyCount;

    @Value("${fraud.rules.high-frequency-window-seconds:60}")
    private long highFrequencyWindowSeconds;

    @Transactional
    public void screen(TransactionEvent event) {
        if (processedEventRepository.existsById(event.eventId())) return;

        checkLargeAmount(event);
        checkHighFrequency(event);

        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    private void checkLargeAmount(TransactionEvent event) {
        if (event.amount().compareTo(largeAmountThreshold) > 0) {
            raise(event, FraudRuleType.LARGE_AMOUNT, FraudSeverity.HIGH,
                    "Debit of " + event.currency() + " " + event.amount() +
                    " exceeds the single-transaction threshold of " + largeAmountThreshold);
        }
    }

    /**
     * Sliding-window count via a Redis sorted set: score = event timestamp
     * (millis), member = eventId. Old entries outside the window are trimmed
     * on every check, so the set never grows unbounded and works correctly
     * across multiple fraud-service instances (state lives in Redis, not
     * in-process memory - required for this to be horizontally scalable).
     */
    private void checkHighFrequency(TransactionEvent event) {
        String key = "fraud:freq:" + event.accountId();
        long nowMillis = Instant.now().toEpochMilli();
        long windowStart = nowMillis - (highFrequencyWindowSeconds * 1000);

        redisTemplate.opsForZSet().add(key, event.eventId().toString(), nowMillis);
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);
        redisTemplate.expire(key, highFrequencyWindowSeconds * 2, TimeUnit.SECONDS);

        Set<String> recent = redisTemplate.opsForZSet().range(key, 0, -1);
        long count = recent != null ? recent.size() : 0;

        if (count > highFrequencyCount) {
            raise(event, FraudRuleType.HIGH_FREQUENCY, FraudSeverity.MEDIUM,
                    "Account had " + count + " debits within the last " + highFrequencyWindowSeconds + "s " +
                    "(threshold: " + highFrequencyCount + ")");
        }
    }

    private void raise(TransactionEvent event, FraudRuleType rule, FraudSeverity severity, String details) {
        FraudAlert alert = FraudAlert.of(event.transactionId(), event.accountId(), event.amount(),
                rule, severity, details);
        fraudAlertRepository.save(alert);
        notifier.broadcast(alert);
        log.warn("FRAUD ALERT [{}] account={} transaction={} :: {}", severity, event.accountId(), event.transactionId(), details);
    }
}
