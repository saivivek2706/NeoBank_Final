package com.neobank.account.kafka;

import com.neobank.account.service.LedgerAuditService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly ledger integrity audit (trial balance, drift, stuck settlement), one replica at a time via ShedLock. */
@Component
@RequiredArgsConstructor
public class LedgerAuditJob {

    private final LedgerAuditService ledgerAuditService;

    @Scheduled(cron = "${ledger.audit.cron:0 5 * * * *}")
    @SchedulerLock(name = "ledger-integrity-audit", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void run() {
        ledgerAuditService.runAudit();
    }
}
