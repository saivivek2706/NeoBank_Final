package com.neobank.compliance.rule;

import com.neobank.compliance.config.ComplianceProperties;
import com.neobank.compliance.domain.Direction;
import com.neobank.compliance.domain.MonitoredTransaction;
import com.neobank.compliance.domain.Severity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** HIGH_VELOCITY: an unusual number of outgoing transfers in a short window (account takeover, mule activity). */
@Component
public class HighVelocityRule implements AmlRule {

    public static final String CODE = "HIGH_VELOCITY";
    private static final long SECONDS_PER_HOUR = 3600;

    private final ComplianceProperties properties;

    public HighVelocityRule(ComplianceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<AlertCandidate> evaluate(MonitoredTransaction tx, TransactionHistory history) {
        if (tx.getDirection() != Direction.OUT) {
            return Optional.empty();
        }
        ComplianceProperties.Aml aml = properties.getAml();
        long count = history.since(tx.getAccountId(), tx.getOccurredAt().minus(aml.getHighVelocityWindow())).stream()
                .filter(t -> t.getDirection() == Direction.OUT)
                .count();
        if (count < aml.getHighVelocityCount()) {
            return Optional.empty();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outgoingTransfers", count);
        details.put("window", aml.getHighVelocityWindow().toString());
        details.put("limit", aml.getHighVelocityCount());
        return Optional.of(new AlertCandidate(CODE, Severity.MEDIUM, 55,
                String.format("%d outgoing transfers within %s (limit %d)", count, aml.getHighVelocityWindow(), aml.getHighVelocityCount()),
                details, CODE + ":" + tx.getAccountId() + ":" + hourBucket(tx.getOccurredAt())));
    }

    private static long hourBucket(Instant instant) {
        return instant.getEpochSecond() / SECONDS_PER_HOUR;
    }
}
