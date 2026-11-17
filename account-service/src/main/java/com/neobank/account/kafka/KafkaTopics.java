package com.neobank.account.kafka;

/**
 * Kafka topics used in the transfer saga (choreography-style: each service reacts
 * to events and emits the next one, no central orchestrator).
 *
 * Flow for a transfer of X from Account A to Account B:
 *   1. transaction-service -> account.debit.requested   {transactionId, accountId=A, amount}
 *   2. account-service     -> account.debit.completed   OR account.debit.failed
 *   3. transaction-service (on debit.completed) -> account.credit.requested {accountId=B, amount}
 *   4. account-service     -> account.credit.completed  OR account.credit.failed
 *   5a. On credit.completed: transaction-service marks transaction COMPLETED,
 *       publishes transaction.completed (Notification/Fraud/Audit consume this in Phase 2)
 *   5b. On credit.failed: transaction-service publishes account.debit.compensate
 *       so account-service reverses the original debit on Account A, then marks
 *       transaction FAILED.
 *
 * Partitioning: all topics are keyed by accountId so all events for a given
 * account land on the same partition and are processed strictly in order.
 */
public final class KafkaTopics {
    public static final String DEBIT_REQUESTED = "account.debit.requested";
    public static final String DEBIT_COMPLETED = "account.debit.completed";
    public static final String DEBIT_FAILED = "account.debit.failed";
    public static final String DEBIT_COMPENSATE = "account.debit.compensate";

    public static final String CREDIT_REQUESTED = "account.credit.requested";
    public static final String CREDIT_COMPLETED = "account.credit.completed";
    public static final String CREDIT_FAILED = "account.credit.failed";

    public static final String TRANSACTION_COMPLETED = "transaction.completed";
    public static final String TRANSACTION_FAILED = "transaction.failed";

    /** Published when an account is opened; the compliance service screens the owner against sanctions watchlists. */
    public static final String ACCOUNT_OPENED = "account.opened";
    /** Command from the compliance service: freeze or unfreeze an account (sanctions hit / hit cleared). */
    public static final String COMPLIANCE_ACTION = "compliance.account.action";

    private KafkaTopics() {}
}
