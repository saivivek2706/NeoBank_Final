package com.neobank.account.kafka;

import com.neobank.account.service.HoldService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks lapsed holds EXPIRED once a minute (one replica at a time, via ShedLock). */
@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryJob {

    private final HoldService holdService;

    @Scheduled(fixedDelayString = "${holds.expiry-sweep-ms:60000}")
    @SchedulerLock(name = "hold-expiry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void sweep() {
        int expired = holdService.expireDueHolds();
        if (expired > 0) {
            log.info("Expired {} lapsed holds", expired);
        }
    }
}
