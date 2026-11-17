package com.neobank.transaction.domain;

/**
 * Saga state machine for a transfer:
 *
 *   INITIATED --(debit requested)--> DEBIT_PENDING
 *   DEBIT_PENDING --(debit.completed)--> CREDIT_PENDING --(credit requested)
 *   DEBIT_PENDING --(debit.failed)--> FAILED  (never touched receiver, nothing to compensate)
 *   CREDIT_PENDING --(credit.completed)--> COMPLETED
 *   CREDIT_PENDING --(credit.failed)--> COMPENSATING --(debit.compensate sent)--> FAILED
 */
public enum TransactionStatus {
    INITIATED,
    DEBIT_PENDING,
    CREDIT_PENDING,
    COMPENSATING,
    COMPLETED,
    FAILED
}
