package com.neobank.notification.kafka;

/**
 * Notification-service deliberately listens to the granular account.* events
 * rather than only transaction.completed - this mirrors how real banks alert
 * you the instant YOUR account moves, not when the whole transfer finishes.
 * The sender gets a debit alert the moment the debit succeeds; the receiver
 * gets a credit alert independently, on their own timeline.
 */
public final class KafkaTopics {
    public static final String DEBIT_COMPLETED = "account.debit.completed";
    public static final String CREDIT_COMPLETED = "account.credit.completed";
    public static final String TRANSACTION_FAILED = "transaction.failed";

    private KafkaTopics() {}
}
