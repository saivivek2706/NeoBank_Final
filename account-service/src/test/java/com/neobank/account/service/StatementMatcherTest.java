package com.neobank.account.service;

import com.neobank.account.domain.BreakType;
import com.neobank.account.domain.MovementDirection;
import com.neobank.account.service.StatementMatcher.Entry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatementMatcherTest {

    private static Entry in(String ref, String amount) {
        return new Entry(ref, MovementDirection.INFLOW, new BigDecimal(amount), "SGD");
    }

    private static Entry out(String ref, String amount) {
        return new Entry(ref, MovementDirection.OUTFLOW, new BigDecimal(amount), "SGD");
    }

    @Test
    void identicalSidesMatchCompletely_evenWhenDecimalScaleDiffers() {
        var result = StatementMatcher.match(List.of(in("A", "100.00"), out("B", "40")), List.of(in("A", "100"), out("B", "40.0000")));

        assertThat(result.matched()).isEqualTo(2);
        assertThat(result.differences()).isEmpty();
    }

    @Test
    void movementOnTheBankStatementButNotInTheLedger() {
        var result = StatementMatcher.match(List.of(in("A", "100.00"), in("GHOST", "25.00")), List.of(in("A", "100.00")));

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.differences()).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(BreakType.MISSING_IN_LEDGER);
            assertThat(d.reference()).isEqualTo("GHOST");
            assertThat(d.statementAmount()).isEqualByComparingTo("25.00");
            assertThat(d.ledgerAmount()).isNull();
        });
    }

    @Test
    void movementInTheLedgerButNotOnTheBankStatement() {
        var result = StatementMatcher.match(List.of(in("A", "100.00")), List.of(in("A", "100.00"), out("LOST", "60.00")));

        assertThat(result.differences()).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(BreakType.MISSING_IN_STATEMENT);
            assertThat(d.reference()).isEqualTo("LOST");
            assertThat(d.ledgerAmount()).isEqualByComparingTo("60.00");
        });
    }

    @Test
    void amountDifference() {
        var result = StatementMatcher.match(List.of(in("A", "100.00")), List.of(in("A", "99.99")));

        assertThat(result.matched()).isZero();
        assertThat(result.differences()).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(BreakType.AMOUNT_MISMATCH);
            assertThat(d.statementAmount()).isEqualByComparingTo("100.00");
            assertThat(d.ledgerAmount()).isEqualByComparingTo("99.99");
        });
    }

    @Test
    void directionDifference() {
        var result = StatementMatcher.match(List.of(in("A", "100.00")), List.of(out("A", "100.00")));

        assertThat(result.differences()).singleElement().satisfies(d -> assertThat(d.type()).isEqualTo(BreakType.DIRECTION_MISMATCH));
    }

    @Test
    void currencyDifference() {
        var bank = new Entry("A", MovementDirection.INFLOW, new BigDecimal("100.00"), "USD");

        var result = StatementMatcher.match(List.of(bank), List.of(in("A", "100.00")));

        assertThat(result.differences()).singleElement().satisfies(d -> assertThat(d.type()).isEqualTo(BreakType.CURRENCY_MISMATCH));
    }

    @Test
    void duplicateReferenceOnTheStatementIsReportedNotGuessedAt() {
        var result = StatementMatcher.match(List.of(in("A", "100.00"), in("A", "100.00")), List.of(in("A", "100.00")));

        assertThat(result.matched()).isZero();
        assertThat(result.differences()).singleElement()
                .satisfies(d -> assertThat(d.type()).isEqualTo(BreakType.DUPLICATE_REFERENCE_IN_STATEMENT));
    }

    @Test
    void duplicateReferenceInTheLedger() {
        var result = StatementMatcher.match(List.of(in("A", "100.00")), List.of(in("A", "100.00"), in("A", "100.00")));

        assertThat(result.matched()).isZero();
        assertThat(result.differences()).singleElement()
                .satisfies(d -> assertThat(d.type()).isEqualTo(BreakType.DUPLICATE_REFERENCE_IN_LEDGER));
    }

    @Test
    void aQuietDayOnBothSidesIsClean() {
        var result = StatementMatcher.match(List.of(), List.of());

        assertThat(result.matched()).isZero();
        assertThat(result.differences()).isEmpty();
    }

    @Test
    void severalDifferencesAreAllReported() {
        var result = StatementMatcher.match(
                List.of(in("OK", "10.00"), in("DIFF", "5.00"), in("ONLYBANK", "1.00")),
                List.of(in("OK", "10.00"), in("DIFF", "6.00"), in("ONLYBOOK", "2.00")));

        assertThat(result.matched()).isEqualTo(1);
        assertThat(result.differences()).extracting(d -> d.type())
                .containsExactlyInAnyOrder(BreakType.AMOUNT_MISMATCH, BreakType.MISSING_IN_LEDGER, BreakType.MISSING_IN_STATEMENT);
    }
}
