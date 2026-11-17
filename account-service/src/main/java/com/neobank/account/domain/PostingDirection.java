package com.neobank.account.domain;

public enum PostingDirection {
    DEBIT,
    CREDIT;

    public PostingDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
