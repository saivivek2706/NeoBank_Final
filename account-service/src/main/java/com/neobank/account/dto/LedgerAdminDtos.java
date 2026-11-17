package com.neobank.account.dto;

import com.neobank.account.domain.BreakType;
import com.neobank.account.domain.MovementDirection;
import com.neobank.account.domain.ReconciliationBreak;
import com.neobank.account.domain.ReconciliationRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LedgerAdminDtos {

    private LedgerAdminDtos() {
    }

    public record StatementLineRequest(
            @NotBlank @Size(max = 100) String reference,
            @NotNull MovementDirection direction,
            @NotNull @DecimalMin(value = "0.0001", message = "amount must be positive") BigDecimal amount,
            @Size(max = 255) String description
    ) {
    }

    public record StatementUploadRequest(
            @NotBlank @Size(max = 50) String source,
            @NotNull LocalDate statementDate,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull @Valid List<StatementLineRequest> lines
    ) {
    }

    public record ResolveBreakRequest(@NotBlank @Size(max = 500) String note) {
    }

    public record BreakResponse(UUID id, BreakType breakType, String reference, BigDecimal statementAmount,
                                BigDecimal ledgerAmount, String detail, boolean resolved, UUID resolvedBy,
                                Instant resolvedAt, String resolutionNote) {
        public static BreakResponse from(ReconciliationBreak b) {
            return new BreakResponse(b.getId(), b.getBreakType(), b.getReference(), b.getStatementAmount(),
                    b.getLedgerAmount(), b.getDetail(), b.isResolved(), b.getResolvedBy(), b.getResolvedAt(),
                    b.getResolutionNote());
        }
    }

    public record RunSummary(UUID id, UUID statementId, Instant runAt, int statementLines, int ledgerMovements,
                             int matched, int breakCount, String status) {
        public static RunSummary from(ReconciliationRun r) {
            return new RunSummary(r.getId(), r.getStatementId(), r.getRunAt(), r.getStatementLines(),
                    r.getLedgerMovements(), r.getMatched(), r.getBreakCount(), r.getStatus());
        }
    }

    public record RunDetail(RunSummary run, List<BreakResponse> breaks) {
    }
}
