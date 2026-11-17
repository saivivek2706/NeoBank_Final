package com.neobank.account.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.account.kafka.event.ComplianceActionEvent;
import com.neobank.account.kafka.event.TransactionEvent;
import com.neobank.account.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AccountEventConsumer {

    private final AccountService accountService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopics.DEBIT_REQUESTED, groupId = "account-service")
    @SneakyThrows
    public void onDebitRequested(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        log.info("Received debit.requested for transaction={} account={} amount={}",
                event.transactionId(), event.accountId(), event.amount());
        accountService.handleDebitRequested(event);
    }

    @KafkaListener(topics = KafkaTopics.CREDIT_REQUESTED, groupId = "account-service")
    @SneakyThrows
    public void onCreditRequested(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        log.info("Received credit.requested for transaction={} account={} amount={}",
                event.transactionId(), event.accountId(), event.amount());
        accountService.handleCreditRequested(event);
    }

    @KafkaListener(topics = KafkaTopics.DEBIT_COMPENSATE, groupId = "account-service")
    @SneakyThrows
    public void onDebitCompensate(String payload) {
        TransactionEvent event = objectMapper.readValue(payload, TransactionEvent.class);
        log.warn("Received debit.compensate (rollback) for transaction={} account={} amount={}",
                event.transactionId(), event.accountId(), event.amount());
        accountService.handleDebitCompensate(event);
    }

    @KafkaListener(topics = KafkaTopics.COMPLIANCE_ACTION, groupId = "account-service")
    @SneakyThrows
    public void onComplianceAction(String payload) {
        ComplianceActionEvent event = objectMapper.readValue(payload, ComplianceActionEvent.class);
        log.warn("Received compliance action {} for account={} case={} reason={}",
                event.action(), event.accountId(), event.caseId(), event.reason());
        accountService.handleComplianceAction(event);
    }
}
