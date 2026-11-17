package com.neobank.account.repository;

import com.neobank.account.domain.Hold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    Optional<Hold> findByAccountIdAndReference(UUID accountId, String reference);

    Optional<Hold> findByIdAndAccountId(UUID id, UUID accountId);

    @Query("SELECT h FROM Hold h WHERE h.accountId = :accountId AND h.status = com.neobank.account.domain.HoldStatus.ACTIVE "
            + "AND h.expiresAt > :now ORDER BY h.createdAt DESC")
    List<Hold> findActive(@Param("accountId") UUID accountId, @Param("now") Instant now);

    /** Total currently reserved on the account (ACTIVE and not yet expired). */
    @Query("SELECT COALESCE(SUM(h.amount), 0) FROM Hold h WHERE h.accountId = :accountId "
            + "AND h.status = com.neobank.account.domain.HoldStatus.ACTIVE AND h.expiresAt > :now")
    BigDecimal sumActive(@Param("accountId") UUID accountId, @Param("now") Instant now);

    /** Housekeeping: flips lapsed ACTIVE holds to EXPIRED (they stopped counting against the balance when they lapsed). */
    @Modifying
    @Query("UPDATE Hold h SET h.status = com.neobank.account.domain.HoldStatus.EXPIRED, h.resolvedAt = :now "
            + "WHERE h.status = com.neobank.account.domain.HoldStatus.ACTIVE AND h.expiresAt <= :now")
    int expireDue(@Param("now") Instant now);
}
