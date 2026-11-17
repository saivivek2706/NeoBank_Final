package com.neobank.audit.kafka;

/**
 * Audit-service is the one service that listens to EVERY topic in the saga -
 * that's the whole point of a centralized, immutable audit trail: a single
 * place to reconstruct exactly what happened to any transaction or account,
 * across every service, in order.
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

    /** Not saga steps, but security- and compliance-relevant events that belong in the same immutable trail. */
    public static final String ACCOUNT_OPENED = "account.opened";
    public static final String COMPLIANCE_ACTION = "compliance.account.action";

    public static final String[] ALL_TOPICS = {
            DEBIT_REQUESTED, DEBIT_COMPLETED, DEBIT_FAILED, DEBIT_COMPENSATE,
            CREDIT_REQUESTED, CREDIT_COMPLETED, CREDIT_FAILED,
            TRANSACTION_COMPLETED, TRANSACTION_FAILED,
            ACCOUNT_OPENED, COMPLIANCE_ACTION
    };

    private KafkaTopics() {}
}
