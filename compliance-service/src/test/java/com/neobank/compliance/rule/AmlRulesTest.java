package com.neobank.compliance.rule;

import com.neobank.compliance.config.ComplianceProperties;
import com.neobank.compliance.domain.Direction;
import com.neobank.compliance.domain.MonitoredTransaction;
import com.neobank.compliance.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmlRulesTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    private final UUID account = UUID.randomUUID();
    private final List<MonitoredTransaction> store = new ArrayList<>();
    private ComplianceProperties properties;

    /** An in-memory history: everything stored whose time is at/after `since`. */
    private final TransactionHistory history = (accountId, since) -> store.stream()
            .filter(t -> t.getAccountId().equals(accountId) && !t.getOccurredAt().isBefore(since)).toList();

    @BeforeEach
    void setUp() {
        properties = new ComplianceProperties();   // defaults: threshold 10000, structuring band 8000-10000 x3, velocity 10/h ...
    }

    private MonitoredTransaction tx(Direction direction, String amount, Duration ago) {
        MonitoredTransaction t = MonitoredTransaction.of(UUID.randomUUID(), UUID.randomUUID(), account, direction,
                new BigDecimal(amount), "SGD", NOW.minus(ago));
        store.add(t);
        return t;
    }

    // ---------------------------------------------------------------- LARGE_TRANSACTION

    @Test
    void largeTransaction_firesAtTheThresholdAndAbove_notBelow() {
        LargeTransactionRule rule = new LargeTransactionRule(properties);

        assertThat(rule.evaluate(tx(Direction.OUT, "9999.99", Duration.ZERO), history)).isEmpty();
        var at = rule.evaluate(tx(Direction.OUT, "10000.00", Duration.ZERO), history);

        assertThat(at).isPresent();
        assertThat(at.get().severity()).isEqualTo(Severity.MEDIUM);
        assertThat(at.get().ruleCode()).isEqualTo("LARGE_TRANSACTION");
    }

    @Test
    void largeTransaction_isHighSeverityWhenFiveTimesTheThreshold_andCoversIncomingMoneyToo() {
        LargeTransactionRule rule = new LargeTransactionRule(properties);

        var alert = rule.evaluate(tx(Direction.IN, "50000", Duration.ZERO), history);

        assertThat(alert).isPresent();
        assertThat(alert.get().severity()).isEqualTo(Severity.HIGH);
        assertThat(alert.get().summary()).contains("Incoming");
    }

    @Test
    void largeTransaction_dedupKeyIsPerTransaction() {
        LargeTransactionRule rule = new LargeTransactionRule(properties);
        var a = rule.evaluate(tx(Direction.OUT, "20000", Duration.ZERO), history).orElseThrow();
        var b = rule.evaluate(tx(Direction.OUT, "20000", Duration.ZERO), history).orElseThrow();

        assertThat(a.dedupKey()).isNotEqualTo(b.dedupKey());
    }

    // ---------------------------------------------------------------- STRUCTURING

    @Test
    void structuring_firesOnRepeatedTransfersJustUnderTheThreshold() {
        StructuringRule rule = new StructuringRule(properties);
        tx(Direction.OUT, "9000", Duration.ofHours(5));
        tx(Direction.OUT, "9500", Duration.ofHours(3));
        MonitoredTransaction latest = tx(Direction.OUT, "9900", Duration.ZERO);

        var alert = rule.evaluate(latest, history);

        assertThat(alert).isPresent();
        assertThat(alert.get().severity()).isEqualTo(Severity.HIGH);
        assertThat(alert.get().details()).containsEntry("count", 3);
        assertThat(alert.get().details().get("total")).isEqualTo(new BigDecimal("28400"));
    }

    @Test
    void structuring_needsEnoughTransfers() {
        StructuringRule rule = new StructuringRule(properties);
        tx(Direction.OUT, "9000", Duration.ofHours(3));
        MonitoredTransaction latest = tx(Direction.OUT, "9900", Duration.ZERO);

        assertThat(rule.evaluate(latest, history)).isEmpty();   // only 2 of the required 3
    }

    @Test
    void structuring_ignoresAmountsOutsideTheBand_andTheTransactionItselfMustBeInTheBand() {
        StructuringRule rule = new StructuringRule(properties);
        tx(Direction.OUT, "9000", Duration.ofHours(5));
        tx(Direction.OUT, "9500", Duration.ofHours(3));
        tx(Direction.OUT, "5000", Duration.ofHours(2));           // well below the band: does not count
        MonitoredTransaction smallLatest = tx(Direction.OUT, "5000", Duration.ZERO);

        assertThat(rule.evaluate(smallLatest, history)).isEmpty();
        assertThat(rule.evaluate(tx(Direction.OUT, "9999", Duration.ZERO), history)).isPresent();   // 3 in band now
    }

    @Test
    void structuring_ignoresTransfersOutsideTheWindow_andTheOtherDirection() {
        StructuringRule rule = new StructuringRule(properties);
        tx(Direction.OUT, "9000", Duration.ofHours(30));          // older than the 24h window
        tx(Direction.IN, "9500", Duration.ofHours(3));            // different direction
        MonitoredTransaction latest = tx(Direction.OUT, "9900", Duration.ZERO);

        assertThat(rule.evaluate(latest, history)).isEmpty();
    }

    @Test
    void structuring_aSingleLargeTransactionIsNotStructuring() {
        StructuringRule rule = new StructuringRule(properties);

        assertThat(rule.evaluate(tx(Direction.OUT, "10000", Duration.ZERO), history)).isEmpty();   // at the threshold, not "under" it
    }

    @Test
    void structuring_oneAlertPerAccountDirectionAndDay() {
        StructuringRule rule = new StructuringRule(properties);
        tx(Direction.OUT, "9000", Duration.ofHours(5));
        tx(Direction.OUT, "9500", Duration.ofHours(3));
        var first = rule.evaluate(tx(Direction.OUT, "9900", Duration.ZERO), history).orElseThrow();
        var second = rule.evaluate(tx(Direction.OUT, "9800", Duration.ofMinutes(-1)), history).orElseThrow();

        assertThat(first.dedupKey()).isEqualTo(second.dedupKey());
    }

    // ---------------------------------------------------------------- RAPID_MOVEMENT

    @Test
    void rapidMovement_firesWhenReceivedMoneyIsPassedStraightOn() {
        RapidMovementRule rule = new RapidMovementRule(properties);
        tx(Direction.IN, "5000", Duration.ofHours(4));
        MonitoredTransaction out = tx(Direction.OUT, "4800", Duration.ZERO);

        var alert = rule.evaluate(out, history);

        assertThat(alert).isPresent();
        assertThat(alert.get().severity()).isEqualTo(Severity.HIGH);
        assertThat(alert.get().details().get("sentToReceivedRatio")).isEqualTo(new BigDecimal("0.96"));
    }

    @Test
    void rapidMovement_doesNotFireWhenMostOfTheMoneyStays() {
        RapidMovementRule rule = new RapidMovementRule(properties);
        tx(Direction.IN, "5000", Duration.ofHours(4));

        assertThat(rule.evaluate(tx(Direction.OUT, "2000", Duration.ZERO), history)).isEmpty();
    }

    @Test
    void rapidMovement_ignoresSmallAmounts_incomingEvents_andOldReceipts() {
        RapidMovementRule rule = new RapidMovementRule(properties);
        tx(Direction.IN, "500", Duration.ofHours(2));             // below the 1000 minimum
        assertThat(rule.evaluate(tx(Direction.OUT, "500", Duration.ZERO), history)).isEmpty();

        store.clear();
        tx(Direction.IN, "5000", Duration.ofHours(30));           // outside the window
        assertThat(rule.evaluate(tx(Direction.OUT, "5000", Duration.ZERO), history)).isEmpty();

        MonitoredTransaction incoming = tx(Direction.IN, "9000", Duration.ZERO);
        assertThat(rule.evaluate(incoming, history)).isEmpty();   // only evaluated on outgoing movements
    }

    // ---------------------------------------------------------------- HIGH_VELOCITY

    @Test
    void highVelocity_firesAtTheConfiguredCount() {
        HighVelocityRule rule = new HighVelocityRule(properties);
        for (int i = 9; i >= 1; i--) {
            tx(Direction.OUT, "10", Duration.ofMinutes(i));
        }
        MonitoredTransaction tenth = tx(Direction.OUT, "10", Duration.ZERO);

        var alert = rule.evaluate(tenth, history);

        assertThat(alert).isPresent();
        assertThat(alert.get().severity()).isEqualTo(Severity.MEDIUM);
        assertThat(alert.get().details()).containsEntry("outgoingTransfers", 10L);
    }

    @Test
    void highVelocity_doesNotFireBelowTheCount_orForOldOrIncomingTransfers() {
        HighVelocityRule rule = new HighVelocityRule(properties);
        for (int i = 8; i >= 1; i--) {
            tx(Direction.OUT, "10", Duration.ofMinutes(i));
        }
        tx(Direction.OUT, "10", Duration.ofHours(3));             // outside the 1h window
        tx(Direction.IN, "10", Duration.ofMinutes(1));            // wrong direction

        assertThat(rule.evaluate(tx(Direction.OUT, "10", Duration.ZERO), history)).isEmpty();   // 9 in the window, need 10
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void thresholdsComeFromConfiguration() {
        properties.getAml().setLargeTransactionThreshold(new BigDecimal("500"));
        properties.getAml().setStructuringMinCount(2);
        LargeTransactionRule large = new LargeTransactionRule(properties);
        StructuringRule structuring = new StructuringRule(properties);

        assertThat(large.evaluate(tx(Direction.OUT, "600", Duration.ZERO), history)).isPresent();

        store.clear();
        tx(Direction.OUT, "450", Duration.ofHours(1));
        assertThat(structuring.evaluate(tx(Direction.OUT, "480", Duration.ZERO), history)).isPresent();   // 2 in the 400-500 band
    }
}
