package com.neobank.account.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FeeCalculatorTest {

    private FeeCalculator calc(String flat, String percent, String min, String max) {
        return new FeeCalculator(new FeeProperties(new BigDecimal(flat), new BigDecimal(percent),
                new BigDecimal(min), new BigDecimal(max)));
    }

    @Test
    void noFeesByDefault() {
        assertThat(calc("0", "0", "0", "0").feeFor(new BigDecimal("1000.00"), "SGD")).isEqualByComparingTo("0");
    }

    @Test
    void flatFee() {
        assertThat(calc("0.50", "0", "0", "0").feeFor(new BigDecimal("100.00"), "SGD")).isEqualByComparingTo("0.50");
    }

    @Test
    void percentageFee_isRoundedHalfEvenToTheCurrencyScale() {
        // 0.125% of 100.00 = 0.125 -> banker's rounding to 2dp = 0.12 (not 0.13)
        assertThat(calc("0", "0.125", "0", "0").feeFor(new BigDecimal("100.00"), "SGD")).isEqualByComparingTo("0.12");
        // 0.135% of 100.00 = 0.135 -> 0.14
        assertThat(calc("0", "0.135", "0", "0").feeFor(new BigDecimal("100.00"), "SGD")).isEqualByComparingTo("0.14");
    }

    @Test
    void flatPlusPercent() {
        assertThat(calc("1.00", "0.10", "0", "0").feeFor(new BigDecimal("500.00"), "SGD")).isEqualByComparingTo("1.50");
    }

    @Test
    void minimumAndMaximumClampTheFee() {
        FeeCalculator c = calc("0", "1", "2.00", "5.00");
        assertThat(c.feeFor(new BigDecimal("10.00"), "SGD")).isEqualByComparingTo("2.00");     // 0.10 -> floor 2.00
        assertThat(c.feeFor(new BigDecimal("300.00"), "SGD")).isEqualByComparingTo("3.00");    // inside the band
        assertThat(c.feeFor(new BigDecimal("10000.00"), "SGD")).isEqualByComparingTo("5.00");  // 100 -> cap 5.00
    }

    @Test
    void respectsCurrenciesWithNoDecimals() {
        assertThat(calc("0", "0.5", "0", "0").feeFor(new BigDecimal("1001"), "JPY")).isEqualByComparingTo("5");
    }

    @Test
    void unknownCurrencyFallsBackToTwoDecimals() {
        assertThat(calc("0.333", "0", "0", "0").feeFor(new BigDecimal("10"), "XXZ")).isEqualByComparingTo("0.33");
    }

    @Test
    void nonPositiveAmountsNeverIncurAFee() {
        assertThat(calc("1", "1", "1", "0").feeFor(BigDecimal.ZERO, "SGD")).isEqualByComparingTo("0");
    }
}
