package com.neobank.compliance.service;

import com.neobank.compliance.repository.WatchlistAliasRepository;
import com.neobank.compliance.repository.WatchlistEntryRepository;
import com.neobank.compliance.service.WatchlistIndex.Candidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WatchlistIndexTest {

    private final UUID entryA = UUID.randomUUID();
    private final UUID entryB = UUID.randomUUID();
    private final UUID oldVersion = UUID.randomUUID();
    private final UUID newVersion = UUID.randomUUID();
    private WatchlistIndex index;

    private Candidate candidate(UUID entry, String source, String name, String via, UUID version) {
        return new Candidate(entry, source, name, NameNormalizer.normalize(name), via, version);
    }

    @BeforeEach
    void setUp() {
        index = new WatchlistIndex(mock(WatchlistEntryRepository.class), mock(WatchlistAliasRepository.class));
        index.replace(List.of(
                candidate(entryA, "OFAC_SDN", "Ivan Petrov", "PRIMARY", oldVersion),
                candidate(entryA, "OFAC_SDN", "Ivan Petroff", "ALIAS", oldVersion),
                candidate(entryB, "UN", "Acme Trading LLC", "PRIMARY", newVersion)));
    }

    @Test
    void anExactNameIsAStrongMatchOnThePrimaryName() {
        var matches = index.screen("IVAN PETROV", 0.88, c -> true);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).score()).isEqualTo(1.0);
        assertThat(matches.get(0).entryId()).isEqualTo(entryA);
        assertThat(matches.get(0).via()).isEqualTo("PRIMARY");
    }

    @Test
    void anAliasCanBeTheBestMatch_andAnEntryIsReportedOnlyOnce() {
        var matches = index.screen("Ivan Petroff", 0.88, c -> true);

        assertThat(matches).hasSize(1);   // entry A matches via its alias (1.0) and fuzzily via the primary name; reported once
        assertThat(matches.get(0).via()).isEqualTo("ALIAS");
        assertThat(matches.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void nothingBelowTheThresholdIsReturned() {
        assertThat(index.screen("Jane Doe", 0.88, c -> true)).isEmpty();
        assertThat(index.screen("", 0.88, c -> true)).isEmpty();
        assertThat(index.screen("   ", 0.88, c -> true)).isEmpty();
    }

    @Test
    void theEligibilityFilterRestrictsWhichEntriesAreConsidered() {
        // a previously cleared customer is only re-checked against entries added by the newest import
        assertThat(index.screen("Ivan Petrov", 0.88, c -> newVersion.equals(c.versionId()))).isEmpty();
        assertThat(index.screen("Acme Trading LLC", 0.88, c -> newVersion.equals(c.versionId()))).hasSize(1);
    }

    @Test
    void resultsAreOrderedStrongestFirst() {
        index.replace(List.of(
                candidate(entryA, "L", "John Smithson", "PRIMARY", oldVersion),
                candidate(entryB, "L", "John Smith", "PRIMARY", oldVersion)));

        var matches = index.screen("John Smith", 0.88, c -> true);

        assertThat(matches).extracting(WatchlistIndex.Match::entryId).containsExactly(entryB, entryA);
    }

    @Test
    void anEmptyIndexMatchesNothing() {
        index.replace(List.of());

        assertThat(index.screen("Ivan Petrov", 0.0, c -> true)).isEmpty();
        assertThat(index.size()).isZero();
    }
}
