package com.neobank.fraud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // processed_events retention job
public class FraudServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FraudServiceApplication.class, args);
    }
}
