package com.neobank.compliance.rule;

import com.neobank.compliance.config.ComplianceProperties;
import com.neobank.compliance.domain.Direction;
import com.neobank.compliance.domain.MonitoredTransaction;
import com.neobank.compliance.domain.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * STRUCTURING ("smurfing"): breaking one large amount into several movements that each stay just under the reporting
 * threshold to avoid it. Fires when, in the window, at least `structuring-min-count` movements in the same direction each
 * fall in [lower-ratio x threshold, threshold) AND together they reach the threshold.
 */
@Component
public class StructuringRule implements AmlRule {

    public static final String CODE = "STRUCTURING";

    private final ComplianceProperties properties;

    public StructuringRule(ComplianceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<AlertCandidate> evaluate(MonitoredTransaction tx, TransactionHistory history) {
        ComplianceProperties.Aml aml = properties.getAml();
        BigDecimal threshold = aml.getLargeTransactionThreshold();
        BigDecimal lowerBound = threshold.multiply(aml.getStructuringLowerRatio());

        if (!inBand(tx.getAmount(), lowerBound, threshold)) {
            return Optional.empty();   // this movement is not "just below the threshold", so it cannot be part of a pattern
        }
        Instant since = tx.getOccurredAt().minus(aml.getStructuringWindow());
        List<MonitoredTransaction> band = history.since(tx.getAccountId(), since).stream()
                .filter(t -> t.getDirection() == tx.getDirection())
                .filter(t -> inBand(t.getAmount(), lowerBound, threshold))
                .toList();
        BigDecimal total = band.stream().map(MonitoredTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (band.size() < aml.getStructuringMinCount() || total.compareTo(threshold) < 0) {
            return Optional.empty();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", band.size());
        details.put("total", total);
        details.put("threshold", threshold);
        details.put("band", lowerBound.toPlainString() + " to " + threshold.toPlainString());
        details.put("window", aml.getStructuringWindow().toString());
        details.put("direction", tx.getDirection());
        return Optional.of(new AlertCandidate(CODE, Severity.HIGH, 80,
                String.format("%d %s transfers between %s and %s within %s total %s - possible structuring below the %s threshold",
                        band.size(), tx.getDirection() == Direction.IN ? "incoming" : "outgoing",
                        lowerBound.toPlainString(), threshold.toPlainString(), aml.getStructuringWindow(),
                        total.toPlainString(), threshold.toPlainString()),
                details, CODE + ":" + tx.getAccountId() + ":" + tx.getDirection() + ":" + day(tx.getOccurredAt())));
    }

    private static boolean inBand(BigDecimal amount, BigDecimal lower, BigDecimal upperExclusive) {
        return amount.compareTo(lower) >= 0 && amount.compareTo(upperExclusive) < 0;
    }

    private static String day(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().toString();
    }
}
