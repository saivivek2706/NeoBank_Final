package com.neobank.compliance.kafka;

public final class KafkaTopics {

    private KafkaTopics() {
    }

    /** Consumed: a new account/party to screen against sanctions watchlists. */
    public static final String ACCOUNT_OPENED = "account.opened";
    /** Consumed: money left an account (the monitored transaction's OUT leg). */
    public static final String DEBIT_COMPLETED = "account.debit.completed";
    /** Consumed: money arrived in an account (the IN leg). */
    public static final String CREDIT_COMPLETED = "account.credit.completed";
    /** Produced: freeze / unfreeze commands that the account service obeys. */
    public static final String COMPLIANCE_ACTION = "compliance.account.action";
}
