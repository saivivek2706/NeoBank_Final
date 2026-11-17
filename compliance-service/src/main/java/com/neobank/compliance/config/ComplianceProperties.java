package com.neobank.compliance.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/** Tunable compliance policy (compliance.* in application.yml). Defaults are conservative and documented in the runbook. */
@ConfigurationProperties(prefix = "compliance")
@Getter
@Setter
public class ComplianceProperties {

    private Aml aml = new Aml();
    private Sanctions sanctions = new Sanctions();
    private Cases cases = new Cases();

    @Getter
    @Setter
    public static class Aml {
        private BigDecimal largeTransactionThreshold = new BigDecimal("10000");
        private Duration structuringWindow = Duration.ofHours(24);
        private BigDecimal structuringLowerRatio = new BigDecimal("0.80");
        private int structuringMinCount = 3;
        private Duration rapidMovementWindow = Duration.ofHours(24);
        private BigDecimal rapidMovementMinAmount = new BigDecimal("1000");
        private BigDecimal rapidMovementOutRatio = new BigDecimal("0.90");
        private int highVelocityCount = 10;
        private Duration highVelocityWindow = Duration.ofHours(1);
    }

    @Getter
    @Setter
    public static class Sanctions {
        private double reviewThreshold = 0.88;
        private boolean freezeOnPotentialMatch = false;
    }

    @Getter
    @Setter
    public static class Cases {
        private Duration slaCritical = Duration.ofHours(24);
        private Duration slaHigh = Duration.ofHours(72);
        private Duration slaMedium = Duration.ofDays(7);
        private Duration slaLow = Duration.ofDays(14);
    }
}
