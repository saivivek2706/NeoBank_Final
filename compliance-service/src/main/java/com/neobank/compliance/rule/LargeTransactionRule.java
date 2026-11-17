package com.neobank.compliance.rule;

import com.neobank.compliance.config.ComplianceProperties;
import com.neobank.compliance.domain.Direction;
import com.neobank.compliance.domain.MonitoredTransaction;
import com.neobank.compliance.domain.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** LARGE_TRANSACTION: a single movement at or above the reporting threshold (either direction). */
@Component
public class LargeTransactionRule implements AmlRule {

    public static final String CODE = "LARGE_TRANSACTION";
    private static final BigDecimal VERY_LARGE_MULTIPLE = BigDecimal.valueOf(5);

    private final ComplianceProperties properties;

    public LargeTransactionRule(ComplianceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Optional<AlertCandidate> evaluate(MonitoredTransaction tx, TransactionHistory history) {
        BigDecimal threshold = properties.getAml().getLargeTransactionThreshold();
        if (tx.getAmount().compareTo(threshold) < 0) {
            return Optional.empty();
        }
        boolean veryLarge = tx.getAmount().compareTo(threshold.multiply(VERY_LARGE_MULTIPLE)) >= 0;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("amount", tx.getAmount());
        details.put("currency", tx.getCurrency());
        details.put("direction", tx.getDirection());
        details.put("threshold", threshold);
        return Optional.of(new AlertCandidate(CODE, veryLarge ? Severity.HIGH : Severity.MEDIUM, veryLarge ? 85 : 60,
                String.format("%s of %s %s is at or above the %s reporting threshold",
                        tx.getDirection() == Direction.IN ? "Incoming transfer" : "Outgoing transfer",
                        tx.getAmount().toPlainString(), tx.getCurrency(), threshold.toPlainString()),
                details, CODE + ":" + tx.getTransactionId() + ":" + tx.getAccountId() + ":" + tx.getDirection()));
    }
}
