package com.neobank.notification.repository;

import com.neobank.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    List<Notification> findByAccountIdOrderBySentAtDesc(UUID accountId);
}
