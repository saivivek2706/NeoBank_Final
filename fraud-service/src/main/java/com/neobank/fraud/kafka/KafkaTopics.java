package com.neobank.fraud.kafka;

public final class KafkaTopics {
    // Fraud screening runs on completed debits - by the time money has actually
    // left an account is when a real fraud team cares most, though a stricter
    // system could also screen at debit.requested (pre-authorization) time.
    public static final String DEBIT_COMPLETED = "account.debit.completed";

    private KafkaTopics() {}
}
