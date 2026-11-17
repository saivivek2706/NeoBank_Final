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
@Table(name = "watchlist_entries")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class WatchlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 50)
    private String listSource;

    @Column(nullable = false, length = 100)
    private String externalId;

    @Column(nullable = false, length = 255)
    private String primaryName;

    @Column(nullable = false, length = 255)
    private String normalizedName;

    @Column(length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntityType entityType;

    @Column(nullable = false)
    private boolean active;

    /** The import that last added or changed this entry - lets a re-screen look only at what is new. */
    @Column(nullable = false)
    private UUID versionId;

    @Column(nullable = false)
    private Instant createdAt;

    public static WatchlistEntry of(String listSource, String externalId, String primaryName, String normalizedName,
                                    String country, EntityType entityType, UUID versionId) {
        WatchlistEntry e = new WatchlistEntry();
        e.setListSource(listSource);
        e.setExternalId(externalId);
        e.setPrimaryName(primaryName);
        e.setNormalizedName(normalizedName);
        e.setCountry(country);
        e.setEntityType(entityType);
        e.setActive(true);
        e.setVersionId(versionId);
        e.setCreatedAt(Instant.now());
        return e;
    }

    public void update(String primaryName, String normalizedName, String country, EntityType entityType, UUID versionId) {
        setPrimaryName(primaryName);
        setNormalizedName(normalizedName);
        setCountry(country);
        setEntityType(entityType);
        setActive(true);
        setVersionId(versionId);
    }

    public void deactivate() {
        setActive(false);
    }

}
