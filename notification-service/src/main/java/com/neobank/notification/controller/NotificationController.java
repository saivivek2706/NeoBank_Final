package com.neobank.notification.controller;

import com.neobank.notification.dto.NotificationResponse;
import com.neobank.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;

    @GetMapping("/account/{accountId}")
    public List<NotificationResponse> forAccount(@PathVariable UUID accountId) {
        return notificationRepository.findByAccountIdOrderBySentAtDesc(accountId)
                .stream().map(NotificationResponse::from).toList();
    }
}
