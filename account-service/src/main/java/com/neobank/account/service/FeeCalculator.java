package com.neobank.account.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Computes the fee for a transfer from the configured schedule. Rounded half-even (banker's rounding) to the
 * currency's own number of decimal places, so fees never carry fractions of a cent.
 */
@Component
public class FeeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final FeeProperties properties;

    public FeeCalculator(FeeProperties properties) {
        this.properties = properties;
    }

    public BigDecimal feeFor(BigDecimal amount, String currency) {
        boolean feesEnabled = properties.flat().signum() > 0 || properties.percent().signum() > 0;
        if (!feesEnabled || amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal fee = properties.flat().add(amount.multiply(properties.percent()).divide(HUNDRED));
        if (fee.compareTo(properties.minimum()) < 0) {
            fee = properties.minimum();
        }
        if (properties.maximum().signum() > 0 && fee.compareTo(properties.maximum()) > 0) {
            fee = properties.maximum();
        }
        fee = fee.setScale(fractionDigits(currency), RoundingMode.HALF_EVEN);
        return fee.signum() > 0 ? fee : BigDecimal.ZERO;
    }

    private static int fractionDigits(String currency) {
        try {
            int digits = Currency.getInstance(currency).getDefaultFractionDigits();
            return digits < 0 ? 2 : digits;
        } catch (IllegalArgumentException unknownCurrency) {
            return 2;
        }
    }
}
