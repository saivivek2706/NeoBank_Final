package com.neobank.account.domain;

public enum OutboxStatus {
    PENDING,
    SENT,
    DEAD // exhausted retries, needs manual/ops intervention - routed to DLT
}
