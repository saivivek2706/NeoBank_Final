package com.neobank.audit.controller;

import com.neobank.audit.dto.AuditEventResponse;
import com.neobank.audit.repository.AuditEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * NOTE: in a real bank this entire controller would be admin/compliance-only
 * (role check via the JWT `role` claim). Left open to any authenticated user
 * here to keep the demo simple to click through end-to-end.
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditEventRepository auditEventRepository;

    @GetMapping("/transaction/{transactionId}")
    public List<AuditEventResponse> forTransaction(@PathVariable UUID transactionId) {
        return auditEventRepository.findByTransactionIdOrderByOccurredAtAsc(transactionId)
                .stream().map(AuditEventResponse::from).toList();
    }

    @GetMapping("/account/{accountId}")
    public List<AuditEventResponse> forAccount(@PathVariable UUID accountId) {
        return auditEventRepository.findByAccountIdOrderByOccurredAtDesc(accountId)
                .stream().map(AuditEventResponse::from).toList();
    }
}
