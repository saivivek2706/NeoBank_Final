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
import java.util.UUID;

@Entity
@Table(name = "watchlist_aliases")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class WatchlistAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID entryId;

    @Column(nullable = false, length = 255)
    private String alias;

    @Column(nullable = false, length = 255)
    private String normalizedAlias;

    public static WatchlistAlias of(UUID entryId, String alias, String normalizedAlias) {
        WatchlistAlias a = new WatchlistAlias();
        a.setEntryId(entryId);
        a.setAlias(alias);
        a.setNormalizedAlias(normalizedAlias);
        return a;
    }

}
