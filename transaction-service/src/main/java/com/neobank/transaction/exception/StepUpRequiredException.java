package com.neobank.transaction.exception;

/** Thrown when a transfer exceeds the step-up threshold but no valid step-up assertion was supplied. */
public class StepUpRequiredException extends RuntimeException {
    public StepUpRequiredException(String message) {
        super(message);
    }
}
