package com.neobank.compliance.rule;

import com.neobank.compliance.domain.MonitoredTransaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An account's monitored transactions from `since` (inclusive) up to now, oldest first. */
@FunctionalInterface
public interface TransactionHistory {
    List<MonitoredTransaction> since(UUID accountId, Instant since);
}
