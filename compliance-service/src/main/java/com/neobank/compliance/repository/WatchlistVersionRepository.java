package com.neobank.compliance.repository;

import com.neobank.compliance.domain.WatchlistVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchlistVersionRepository extends JpaRepository<WatchlistVersion, UUID> {
    List<WatchlistVersion> findTop20ByOrderByImportedAtDesc();

    Optional<WatchlistVersion> findFirstByOrderByImportedAtDesc();
}
