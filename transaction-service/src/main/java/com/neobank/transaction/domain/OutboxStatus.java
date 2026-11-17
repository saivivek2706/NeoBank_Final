package com.neobank.transaction.domain;

public enum OutboxStatus {
    PENDING,
    SENT,
    DEAD
}
