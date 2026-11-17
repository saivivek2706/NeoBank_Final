package com.neobank.compliance.rule;

import com.neobank.compliance.domain.MonitoredTransaction;

import java.util.Optional;

/**
 * One transaction-monitoring scenario. Pure: it sees the transaction that just happened and the account's recent history
 * (which already includes that transaction) and either raises an alert candidate or not. All I/O lives in MonitoringService.
 */
public interface AmlRule {

    String code();

    Optional<AlertCandidate> evaluate(MonitoredTransaction transaction, TransactionHistory history);
}
