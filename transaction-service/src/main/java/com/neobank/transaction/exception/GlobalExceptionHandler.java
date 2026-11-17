package com.neobank.transaction.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(VelocityLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleVelocityLimit(VelocityLimitExceededException ex) {
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(StepUpRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleStepUpRequired(StepUpRequiredException ex) {
        // 428 Precondition Required: semantically exact - the client needs to
        // complete an additional step (MFA step-up) before this request can
        // be retried, distinguishable from a plain 401/403 so the frontend
        // knows to prompt for a code rather than showing a generic error.
        return build(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyKeyReuse(IdempotencyKeyReuseException ex) {
        // 422 Unprocessable Entity (the convention Stripe uses for this case): the request is
        // well-formed, but the key was already used for a different transfer.
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        ));
    }
}
