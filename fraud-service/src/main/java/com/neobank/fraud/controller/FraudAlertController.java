package com.neobank.fraud.controller;

import com.neobank.fraud.dto.FraudAlertResponse;
import com.neobank.fraud.repository.FraudAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fraud-alerts")
@RequiredArgsConstructor
public class FraudAlertController {

    private final FraudAlertRepository fraudAlertRepository;

    @GetMapping
    public List<FraudAlertResponse> all() {
        return fraudAlertRepository.findAllByOrderByFlaggedAtDesc()
                .stream().map(FraudAlertResponse::from).toList();
    }

    @GetMapping("/account/{accountId}")
    public List<FraudAlertResponse> forAccount(@PathVariable UUID accountId) {
        return fraudAlertRepository.findByAccountIdOrderByFlaggedAtDesc(accountId)
                .stream().map(FraudAlertResponse::from).toList();
    }
}
