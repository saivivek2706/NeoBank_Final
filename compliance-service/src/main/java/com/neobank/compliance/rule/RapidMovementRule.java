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
 * RAPID_MOVEMENT (pass-through / layering): money arrives and almost immediately leaves again, so the account is acting as a
 * conduit rather than a place where funds are kept. Evaluated on each outgoing transfer: if, within the window, at least
 * `rapid-movement-min-amount` came in and at least `rapid-movement-out-ratio` of it has already gone out, raise an alert.
 */
@Component
public class RapidMovementRule implements AmlRule {

    public static final String CODE = "RAPID_MOVEMENT";

    private final ComplianceProperties properties;

    public RapidMovementRule(ComplianceProperties properties) {
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
        Instant since = tx.getOccurredAt().minus(aml.getRapidMovementWindow());
        List<MonitoredTransaction> window = history.since(tx.getAccountId(), since);

        BigDecimal in = sum(window, Direction.IN);
        BigDecimal out = sum(window, Direction.OUT);
        if (in.compareTo(aml.getRapidMovementMinAmount()) < 0 || out.compareTo(in.multiply(aml.getRapidMovementOutRatio())) < 0) {
            return Optional.empty();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("received", in);
        details.put("sent", out);
        details.put("sentToReceivedRatio", out.divide(in, 2, java.math.RoundingMode.HALF_UP));
        details.put("window", aml.getRapidMovementWindow().toString());
        return Optional.of(new AlertCandidate(CODE, Severity.HIGH, 75,
                String.format("%s received and %s sent within %s: the account is passing funds straight through",
                        in.toPlainString(), out.toPlainString(), aml.getRapidMovementWindow()),
                details, CODE + ":" + tx.getAccountId() + ":" + tx.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate()));
    }

    private static BigDecimal sum(List<MonitoredTransaction> txs, Direction direction) {
        return txs.stream().filter(t -> t.getDirection() == direction).map(MonitoredTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
