package com.neobank.auth.exception;

public class AccountLockedException extends RuntimeException {
    public AccountLockedException() {
        super("Too many failed login attempts. This account is temporarily locked - please try again later.");
    }
}
