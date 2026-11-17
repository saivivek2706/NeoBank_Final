package com.neobank.compliance.repository;

import com.neobank.compliance.domain.WatchlistAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WatchlistAliasRepository extends JpaRepository<WatchlistAlias, UUID> {
    List<WatchlistAlias> findByEntryId(UUID entryId);

    List<WatchlistAlias> findByEntryIdIn(Collection<UUID> entryIds);

    void deleteByEntryId(UUID entryId);
}
