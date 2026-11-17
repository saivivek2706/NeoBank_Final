package com.neobank.compliance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "watchlist_versions")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class WatchlistVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID importedBy;

    @Column(nullable = false)
    private Instant importedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WatchlistMode mode;

    @Column(nullable = false)
    private int entryCount;

    @Column(nullable = false)
    private int added;

    @Column(nullable = false)
    private int changed;

    @Column(nullable = false)
    private int deactivated;

    @Column(nullable = false, length = 64)
    private String checksum;

    public void recordCounts(int entryCount, int added, int changed, int deactivated) {
        setEntryCount(entryCount);
        setAdded(added);
        setChanged(changed);
        setDeactivated(deactivated);
    }

    public static WatchlistVersion of(UUID importedBy, WatchlistMode mode, int entryCount, int added, int changed,
                                      int deactivated, String checksum) {
        WatchlistVersion v = new WatchlistVersion();
        v.setImportedBy(importedBy);
        v.setImportedAt(Instant.now());
        v.setMode(mode);
        v.setEntryCount(entryCount);
        v.setAdded(added);
        v.setChanged(changed);
        v.setDeactivated(deactivated);
        v.setChecksum(checksum);
        return v;
    }

}
