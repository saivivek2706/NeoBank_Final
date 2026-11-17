package com.neobank.transaction.kafka;

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

    private KafkaTopics() {}
}
