package com.neobank.compliance.service;

import com.neobank.compliance.domain.EntityType;
import com.neobank.compliance.domain.WatchlistAlias;
import com.neobank.compliance.domain.WatchlistEntry;
import com.neobank.compliance.domain.WatchlistMode;
import com.neobank.compliance.domain.WatchlistVersion;
import com.neobank.compliance.repository.WatchlistAliasRepository;
import com.neobank.compliance.repository.WatchlistEntryRepository;
import com.neobank.compliance.repository.WatchlistVersionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Imports a sanctions list (the file a vendor or regulator publishes, converted to the CSV below) and re-screens every known
 * customer against what changed.
 *
 *   list_source,external_id,name,aliases,country,entity_type
 *   OFAC_SDN,12345,Ivan Petrov,Ivan P. Petrov|Ivan Petroff,RU,INDIVIDUAL
 *
 * `aliases` are separated by '|'. In FULL mode the file is the complete content of each list_source it mentions, so entries
 * of that source that are no longer in it are deactivated (a delisted person stops matching); DELTA only adds/updates.
 * Every import is a numbered version with a checksum of the file, so a screening result can be tied to the exact list used.
 */
@Service
@Slf4j
public class WatchlistService {

    public record ImportResult(UUID versionId, int entries, int added, int changed, int deactivated, int partiesFlagged) {
    }

    record Row(String listSource, String externalId, String name, List<String> aliases, String country, EntityType type) {
    }

    private final WatchlistVersionRepository versionRepository;
    private final WatchlistEntryRepository entryRepository;
    private final WatchlistAliasRepository aliasRepository;
    private final WatchlistIndex index;
    private final ScreeningService screeningService;
    private final MeterRegistry meterRegistry;

    public WatchlistService(WatchlistVersionRepository versionRepository,
                            WatchlistEntryRepository entryRepository,
                            WatchlistAliasRepository aliasRepository,
                            WatchlistIndex index,
                            ScreeningService screeningService,
                            MeterRegistry meterRegistry) {
        this.versionRepository = versionRepository;
        this.entryRepository = entryRepository;
        this.aliasRepository = aliasRepository;
        this.index = index;
        this.screeningService = screeningService;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ImportResult importCsv(String csv, WatchlistMode mode, UUID importedBy) {
        List<Row> rows = parse(csv);
        WatchlistVersion version = versionRepository.save(WatchlistVersion.of(importedBy, mode, rows.size(), 0, 0, 0, sha256(csv)));

        int added = 0;
        int changed = 0;
        Set<String> seenKeys = new HashSet<>();
        for (Row row : rows) {
            if (!seenKeys.add(row.listSource() + "|" + row.externalId())) {
                throw new IllegalArgumentException("Duplicate entry " + row.listSource() + "/" + row.externalId() + " in the file");
            }
            WatchlistEntry existing = entryRepository.findByListSourceAndExternalId(row.listSource(), row.externalId()).orElse(null);
            String normalized = NameNormalizer.normalize(row.name());
            if (existing == null) {
                WatchlistEntry created = entryRepository.save(WatchlistEntry.of(row.listSource(), row.externalId(), row.name(),
                        normalized, row.country(), row.type(), version.getId()));
                replaceAliases(created.getId(), row.aliases());
                added++;
            } else if (differs(existing, row, normalized)) {
                existing.update(row.name(), normalized, row.country(), row.type(), version.getId());
                entryRepository.save(existing);
                replaceAliases(existing.getId(), row.aliases());
                changed++;
            }
        }

        int deactivated = 0;
        if (mode == WatchlistMode.FULL) {
            Set<String> sources = rows.stream().map(Row::listSource).collect(Collectors.toSet());
            for (String source : sources) {
                for (WatchlistEntry entry : entryRepository.findByListSourceAndActiveTrue(source)) {
                    if (!seenKeys.contains(source + "|" + entry.getExternalId())) {
                        entry.deactivate();
                        entryRepository.save(entry);
                        deactivated++;
                    }
                }
            }
        }
        version.recordCounts(rows.size(), added, changed, deactivated);
        versionRepository.save(version);

        // Rebuild the in-memory index from this transaction's view so the re-screen below uses the new list; if the
        // transaction rolls back, rebuild again so the index never keeps entries that were never committed.
        index.refresh();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        index.refresh();
                    }
                }
            });
        }

        int flagged = screeningService.rescreenAll(version.getId());
        meterRegistry.counter("neobank.sanctions.watchlist.imports", "mode", mode.name()).increment();
        log.info("Watchlist import {}: {} entries ({} added, {} changed, {} deactivated), {} customers newly flagged",
                version.getId(), rows.size(), added, changed, deactivated, flagged);
        return new ImportResult(version.getId(), rows.size(), added, changed, deactivated, flagged);
    }

    private boolean differs(WatchlistEntry existing, Row row, String normalized) {
        if (!existing.isActive() || !existing.getNormalizedName().equals(normalized)
                || existing.getEntityType() != row.type()
                || !Objects.equals(existing.getCountry(), row.country())) {
            return true;
        }
        Set<String> current = aliasRepository.findByEntryId(existing.getId()).stream()
                .map(WatchlistAlias::getNormalizedAlias).collect(Collectors.toSet());
        Set<String> incoming = row.aliases().stream().map(NameNormalizer::normalize).filter(a -> !a.isEmpty()).collect(Collectors.toSet());
        return !current.equals(incoming);
    }

    private void replaceAliases(UUID entryId, List<String> aliases) {
        aliasRepository.deleteByEntryId(entryId);
        Set<String> seen = new HashSet<>();
        for (String alias : aliases) {
            String normalized = NameNormalizer.normalize(alias);
            if (!normalized.isEmpty() && seen.add(normalized)) {
                aliasRepository.save(WatchlistAlias.of(entryId, alias.trim(), normalized));
            }
        }
    }

    static List<Row> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("Watchlist CSV is empty");
        }
        String[] lines = csv.split("\\r?\\n");
        List<String> header = CsvLine.split(lines[0]);
        if (header.size() < 3 || !header.get(0).trim().equalsIgnoreCase("list_source")
                || !header.get(1).trim().equalsIgnoreCase("external_id") || !header.get(2).trim().equalsIgnoreCase("name")) {
            throw new IllegalArgumentException("Line 1: header must start with list_source,external_id,name[,aliases,country,entity_type]");
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            int lineNo = i + 1;
            List<String> f = CsvLine.split(lines[i]);
            if (f.size() < 3) {
                throw new IllegalArgumentException("Line " + lineNo + ": expected at least list_source,external_id,name");
            }
            String source = f.get(0).trim();
            String externalId = f.get(1).trim();
            String name = f.get(2).trim();
            if (source.isEmpty() || source.length() > 50 || externalId.isEmpty() || externalId.length() > 100) {
                throw new IllegalArgumentException("Line " + lineNo + ": list_source (max 50) and external_id (max 100) are required");
            }
            if (NameNormalizer.normalize(name).isEmpty() || name.length() > 255) {
                throw new IllegalArgumentException("Line " + lineNo + ": name is required (max 255 characters)");
            }
            List<String> aliases = f.size() > 3 && !f.get(3).isBlank()
                    ? List.of(f.get(3).split("\\|")) : List.of();
            String country = f.size() > 4 && !f.get(4).isBlank() ? f.get(4).trim().toUpperCase(Locale.ROOT) : null;
            if (country != null && !country.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("Line " + lineNo + ": country must be a 2-letter ISO code");
            }
            EntityType type = EntityType.INDIVIDUAL;
            if (f.size() > 5 && !f.get(5).isBlank()) {
                try {
                    type = EntityType.valueOf(f.get(5).trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Line " + lineNo + ": entity_type must be INDIVIDUAL or ORGANIZATION");
                }
            }
            rows.add(new Row(source, externalId, name, aliases, country, type));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Watchlist CSV has a header but no entries");
        }
        return rows;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by every JVM", e);
        }
    }
}
