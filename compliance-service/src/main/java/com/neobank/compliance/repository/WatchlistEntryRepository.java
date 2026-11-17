package com.neobank.compliance.repository;

import com.neobank.compliance.domain.WatchlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WatchlistEntryRepository extends JpaRepository<WatchlistEntry, UUID> {
    Optional<WatchlistEntry> findByListSourceAndExternalId(String listSource, String externalId);

    List<WatchlistEntry> findByActiveTrue();

    List<WatchlistEntry> findByListSourceAndActiveTrue(String listSource);

    Page<WatchlistEntry> findByNormalizedNameContaining(String normalizedFragment, Pageable pageable);

    long countByActiveTrue();
}
