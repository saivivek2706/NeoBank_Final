package com.neobank.transaction.exception;

/** The same Idempotency-Key was sent with a different transfer payload - almost certainly a client bug. */
public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String message) {
        super(message);
    }
}
