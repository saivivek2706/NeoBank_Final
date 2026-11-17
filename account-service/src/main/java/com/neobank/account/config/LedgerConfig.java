package com.neobank.account.config;

import com.neobank.account.service.FeeProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(FeeProperties.class)
public class LedgerConfig {

    /** Injectable clock so hold expiry and reconciliation windows are testable without sleeping. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
