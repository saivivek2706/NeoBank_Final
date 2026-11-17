package com.neobank.compliance.repository;

import com.neobank.compliance.domain.ScreenedParty;
import org.springframework.data.jpa.repository.JpaRepository;
import com.neobank.compliance.domain.PartyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface ScreenedPartyRepository extends JpaRepository<ScreenedParty, UUID> {
    Optional<ScreenedParty> findByAccountId(UUID accountId);

    Page<ScreenedParty> findByStatus(PartyStatus status, Pageable pageable);

    long countByStatus(PartyStatus status);
}
