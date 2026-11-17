package com.neobank.compliance.service;

import com.neobank.compliance.domain.WatchlistAlias;
import com.neobank.compliance.domain.WatchlistEntry;
import com.neobank.compliance.repository.WatchlistAliasRepository;
import com.neobank.compliance.repository.WatchlistEntryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * In-memory index of every ACTIVE watchlist name (primary names and aliases) for fast screening. Rebuilt after each
 * import and periodically, so every replica converges on the latest list.
 *
 * Screening is a linear scan with a cheap early-out; that is fine for the tens of thousands of entries a consolidated
 * sanctions list holds. Beyond that, a real deployment would add blocking (phonetic keys / n-gram prefilter) or move to a
 * vendor screening engine behind the same interface.
 */
@Component
@Slf4j
public class WatchlistIndex {

    /** One searchable name: either an entry's primary name or one of its aliases. */
    public record Candidate(UUID entryId, String listSource, String displayName, String normalized, String via, UUID versionId) {
    }

    public record Match(UUID entryId, String listSource, String matchedName, String via, double score) {
    }

    private static final int MAX_MATCHES = 10;

    private final WatchlistEntryRepository entryRepository;
    private final WatchlistAliasRepository aliasRepository;
    private volatile List<Candidate> candidates = List.of();

    public WatchlistIndex(WatchlistEntryRepository entryRepository, WatchlistAliasRepository aliasRepository) {
        this.entryRepository = entryRepository;
        this.aliasRepository = aliasRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${compliance.sanctions.index-refresh-ms:300000}", initialDelayString = "${compliance.sanctions.index-refresh-ms:300000}")
    @Transactional(readOnly = true)
    public void refresh() {
        List<WatchlistEntry> entries = entryRepository.findByActiveTrue();
        Map<UUID, List<WatchlistAlias>> aliasesByEntry = new HashMap<>();
        if (!entries.isEmpty()) {
            for (WatchlistAlias alias : aliasRepository.findByEntryIdIn(entries.stream().map(WatchlistEntry::getId).toList())) {
                aliasesByEntry.computeIfAbsent(alias.getEntryId(), k -> new ArrayList<>()).add(alias);
            }
        }
        List<Candidate> built = new ArrayList<>();
        for (WatchlistEntry e : entries) {
            built.add(new Candidate(e.getId(), e.getListSource(), e.getPrimaryName(), e.getNormalizedName(), "PRIMARY", e.getVersionId()));
            for (WatchlistAlias a : aliasesByEntry.getOrDefault(e.getId(), List.of())) {
                built.add(new Candidate(e.getId(), e.getListSource(), a.getAlias(), a.getNormalizedAlias(), "ALIAS", e.getVersionId()));
            }
        }
        this.candidates = List.copyOf(built);
        log.info("Watchlist index refreshed: {} active entries, {} searchable names", entries.size(), built.size());
    }

    /** Replaces the index contents directly (used by tests). */
    void replace(List<Candidate> newCandidates) {
        this.candidates = List.copyOf(newCandidates);
    }

    public int size() {
        return candidates.size();
    }

    /**
     * Matches `name` against the index. Returns at most one match per watchlist entry (its best-scoring name), strongest
     * first, keeping only scores at or above `threshold`.
     */
    public List<Match> screen(String name, double threshold, Predicate<Candidate> eligible) {
        String normalized = NameNormalizer.normalize(name);
        if (normalized.isEmpty()) {
            return List.of();
        }
        Map<UUID, Match> bestPerEntry = new HashMap<>();
        for (Candidate c : candidates) {
            if (!eligible.test(c)) {
                continue;
            }
            double score = NameMatcher.score(normalized, c.normalized());
            if (score >= threshold) {
                Match existing = bestPerEntry.get(c.entryId());
                if (existing == null || score > existing.score()) {
                    bestPerEntry.put(c.entryId(), new Match(c.entryId(), c.listSource(), c.displayName(), c.via(), score));
                }
            }
        }
        List<Match> matches = new ArrayList<>(bestPerEntry.values());
        matches.sort(Comparator.comparingDouble(Match::score).reversed());
        return matches.size() > MAX_MATCHES ? matches.subList(0, MAX_MATCHES) : matches;
    }
}
