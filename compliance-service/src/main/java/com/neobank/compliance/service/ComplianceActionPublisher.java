package com.neobank.compliance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.compliance.domain.OutboxEvent;
import com.neobank.compliance.kafka.KafkaTopics;
import com.neobank.compliance.kafka.event.ComplianceActionEvent;
import com.neobank.compliance.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Sends freeze / unfreeze commands to the account service through the transactional outbox, so a command is published
 * if and only if the case/screening decision that caused it was committed.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ComplianceActionPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void freeze(UUID accountId, UUID caseId, String reason) {
        publish(ComplianceActionEvent.of(accountId, ComplianceActionEvent.Action.FREEZE, reason, caseId));
    }

    public void unfreeze(UUID accountId, UUID caseId, String reason) {
        publish(ComplianceActionEvent.of(accountId, ComplianceActionEvent.Action.UNFREEZE, reason, caseId));
    }

    @SneakyThrows
    private void publish(ComplianceActionEvent event) {
        outboxEventRepository.save(OutboxEvent.create("Account", event.accountId().toString(), "ComplianceAction",
                KafkaTopics.COMPLIANCE_ACTION, objectMapper.writeValueAsString(event)));
    }
}
