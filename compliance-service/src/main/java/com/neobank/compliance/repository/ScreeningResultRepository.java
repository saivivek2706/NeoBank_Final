package com.neobank.compliance.repository;

import com.neobank.compliance.domain.ScreeningResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ScreeningResultRepository extends JpaRepository<ScreeningResult, UUID> {
    List<ScreeningResult> findByPartyIdOrderByScreenedAtDesc(UUID partyId);
}
