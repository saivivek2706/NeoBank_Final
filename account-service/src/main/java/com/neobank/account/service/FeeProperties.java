package com.neobank.account.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Transfer fee schedule (fees.transfer.*), charged to the SENDER on top of the transfer amount.
 *
 *   fee = flat + amount * percent / 100, then clamped to [minimum, maximum]
 *
 * Everything defaults to zero, i.e. fees are off until a schedule is configured. `maximum` of 0 means "no cap".
 * Amounts are in the transfer's own currency.
 */
@ConfigurationProperties(prefix = "fees.transfer")
public record FeeProperties(
        @DefaultValue("0") BigDecimal flat,
        @DefaultValue("0") BigDecimal percent,
        @DefaultValue("0") BigDecimal minimum,
        @DefaultValue("0") BigDecimal maximum
) {
}
