package com.neobank.account.dto;

import com.neobank.account.domain.Hold;
import com.neobank.account.domain.HoldStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class HoldDtos {

    private HoldDtos() {
    }

    public record PlaceHoldRequest(
            @NotNull @DecimalMin(value = "0.0001", message = "Hold amount must be positive") BigDecimal amount,
            @NotBlank @Size(max = 100) String reference,
            @Size(max = 255) String reason,
            @Positive Long ttlMinutes
    ) {
    }

    public record CaptureHoldRequest(@DecimalMin(value = "0.0001", message = "Capture amount must be positive") BigDecimal amount) {
    }

    public record HoldResponse(UUID id, UUID accountId, BigDecimal amount, BigDecimal capturedAmount, String currency,
                               HoldStatus status, String reference, String reason, Instant expiresAt,
                               Instant createdAt, Instant resolvedAt) {
        public static HoldResponse from(Hold h) {
            return new HoldResponse(h.getId(), h.getAccountId(), h.getAmount(), h.getCapturedAmount(), h.getCurrency(),
                    h.getStatus(), h.getReference(), h.getReason(), h.getExpiresAt(), h.getCreatedAt(), h.getResolvedAt());
        }
    }
}
