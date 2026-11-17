package com.neobank.compliance.service;

import com.neobank.compliance.domain.EntityType;
import com.neobank.compliance.domain.WatchlistAlias;
import com.neobank.compliance.domain.WatchlistEntry;
import com.neobank.compliance.domain.WatchlistMode;
import com.neobank.compliance.domain.WatchlistVersion;
import com.neobank.compliance.repository.WatchlistAliasRepository;
import com.neobank.compliance.repository.WatchlistEntryRepository;
import com.neobank.compliance.repository.WatchlistVersionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    private static final String HEADER = "list_source,external_id,name,aliases,country,entity_type\n";

    @Mock private WatchlistVersionRepository versionRepository;
    @Mock private WatchlistEntryRepository entryRepository;
    @Mock private WatchlistAliasRepository aliasRepository;
    @Mock private WatchlistIndex index;
    @Mock private ScreeningService screeningService;

    private final UUID admin = UUID.randomUUID();
    private WatchlistService service;

    @BeforeEach
    void setUp() {
        service = new WatchlistService(versionRepository, entryRepository, aliasRepository, index, screeningService, new SimpleMeterRegistry());
        lenient().when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(entryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(entryRepository.findByListSourceAndExternalId(any(), any())).thenReturn(Optional.empty());
        lenient().when(screeningService.rescreenAll(any())).thenReturn(0);
    }

    private static WatchlistEntry entry(String source, String externalId, String name, String country, EntityType type) {
        return WatchlistEntry.of(source, externalId, name, NameNormalizer.normalize(name), country, type, UUID.randomUUID());
    }

    // ---------------------------------------------------------------- parsing

    @Test
    void parsesEntriesWithAliasesCountryAndType() {
        var rows = WatchlistService.parse(HEADER
                + "OFAC_SDN,12345,Ivan Petrov,Ivan P. Petrov|Ivan Petroff,ru,INDIVIDUAL\n"
                + "UN,U-9,\"Acme, Trading LLC\",,SG,organization\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).aliases()).containsExactly("Ivan P. Petrov", "Ivan Petroff");
        assertThat(rows.get(0).country()).isEqualTo("RU");                    // upper-cased
        assertThat(rows.get(1).name()).isEqualTo("Acme, Trading LLC");        // quoted comma preserved
        assertThat(rows.get(1).type()).isEqualTo(EntityType.ORGANIZATION);
        assertThat(rows.get(1).aliases()).isEmpty();
    }

    @Test
    void theOptionalColumnsMayBeOmitted() {
        var rows = WatchlistService.parse("list_source,external_id,name\nINTERNAL,1,Some Person\n");

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo(EntityType.INDIVIDUAL);
            assertThat(r.country()).isNull();
        });
    }

    @Test
    void parseErrorsNameTheLine() {
        assertThatThrownBy(() -> WatchlistService.parse("name,id\nx,1")).hasMessageContaining("Line 1");
        assertThatThrownBy(() -> WatchlistService.parse(HEADER + "OFAC,1,Good Name,,RU,INDIVIDUAL\nOFAC,2,,,RU,INDIVIDUAL"))
                .hasMessageContaining("Line 3").hasMessageContaining("name is required");
        assertThatThrownBy(() -> WatchlistService.parse(HEADER + "OFAC,1,Name,,RUS,INDIVIDUAL")).hasMessageContaining("2-letter");
        assertThatThrownBy(() -> WatchlistService.parse(HEADER + "OFAC,1,Name,,RU,ROBOT")).hasMessageContaining("INDIVIDUAL or ORGANIZATION");
        assertThatThrownBy(() -> WatchlistService.parse(HEADER + ",1,Name")).hasMessageContaining("list_source");
        assertThatThrownBy(() -> WatchlistService.parse(HEADER)).hasMessageContaining("no entries");
        assertThatThrownBy(() -> WatchlistService.parse(" ")).hasMessageContaining("empty");
    }

    // ---------------------------------------------------------------- import

    @Test
    void importAddsNewEntriesAndTheirAliases_thenRebuildsTheIndexAndRescreens() {
        var result = service.importCsv(HEADER + "OFAC_SDN,1,Ivan Petrov,Ivan Petroff|I. Petrov,RU,INDIVIDUAL\n", WatchlistMode.FULL, admin);

        assertThat(result.entries()).isEqualTo(1);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.changed()).isZero();
        assertThat(result.deactivated()).isZero();
        verify(aliasRepository, times(2)).save(any(WatchlistAlias.class));
        verify(index).refresh();
        verify(screeningService).rescreenAll(any());
    }

    @Test
    void anImportRecordsTheFileChecksumAndTheCounts() {
        service.importCsv(HEADER + "OFAC_SDN,1,Ivan Petrov,,RU,INDIVIDUAL\n", WatchlistMode.FULL, admin);

        ArgumentCaptor<WatchlistVersion> version = ArgumentCaptor.forClass(WatchlistVersion.class);
        verify(versionRepository, times(2)).save(version.capture());          // created, then updated with the counts
        WatchlistVersion finalVersion = version.getAllValues().get(1);
        assertThat(finalVersion.getChecksum()).hasSize(64);
        assertThat(finalVersion.getAdded()).isEqualTo(1);
        assertThat(finalVersion.getImportedBy()).isEqualTo(admin);
        assertThat(finalVersion.getMode()).isEqualTo(WatchlistMode.FULL);
    }

    @Test
    void anUnchangedEntryIsLeftAlone_soItIsNotReScreenedAsNew() {
        WatchlistEntry existing = entry("OFAC_SDN", "1", "Ivan Petrov", "RU", EntityType.INDIVIDUAL);
        when(entryRepository.findByListSourceAndExternalId("OFAC_SDN", "1")).thenReturn(Optional.of(existing));
        when(aliasRepository.findByEntryId(any())).thenReturn(List.of());

        var result = service.importCsv(HEADER + "OFAC_SDN,1,Ivan Petrov,,RU,INDIVIDUAL\n", WatchlistMode.FULL, admin);

        assertThat(result.added()).isZero();
        assertThat(result.changed()).isZero();
        verify(entryRepository, never()).save(any());
    }

    @Test
    void aChangedEntryIsUpdatedWithTheNewVersion() {
        WatchlistEntry existing = entry("OFAC_SDN", "1", "Ivan Petrov", "RU", EntityType.INDIVIDUAL);
        UUID oldVersion = existing.getVersionId();
        when(entryRepository.findByListSourceAndExternalId("OFAC_SDN", "1")).thenReturn(Optional.of(existing));

        var result = service.importCsv(HEADER + "OFAC_SDN,1,Ivan Petrov,,BY,INDIVIDUAL\n", WatchlistMode.DELTA, admin);   // country changed

        assertThat(result.changed()).isEqualTo(1);
        assertThat(existing.getCountry()).isEqualTo("BY");
        assertThat(existing.getVersionId()).isNotEqualTo(oldVersion);
    }

    @Test
    void aChangedAliasListCountsAsAChange() {
        WatchlistEntry existing = entry("OFAC_SDN", "1", "Ivan Petrov", "RU", EntityType.INDIVIDUAL);
        when(entryRepository.findByListSourceAndExternalId("OFAC_SDN", "1")).thenReturn(Optional.of(existing));
        when(aliasRepository.findByEntryId(any())).thenReturn(List.of());

        var result = service.importCsv(HEADER + "OFAC_SDN,1,Ivan Petrov,Vanya P,RU,INDIVIDUAL\n", WatchlistMode.FULL, admin);

        assertThat(result.changed()).isEqualTo(1);
    }

    @Test
    void aFullImportDeactivatesEntriesThatLeftTheList_butDeltaDoesNot() {
        WatchlistEntry delisted = entry("OFAC_SDN", "OLD", "Removed Person", "RU", EntityType.INDIVIDUAL);
        when(entryRepository.findByListSourceAndActiveTrue("OFAC_SDN")).thenReturn(List.of(delisted));

        var full = service.importCsv(HEADER + "OFAC_SDN,1,Ivan Petrov,,RU,INDIVIDUAL\n", WatchlistMode.FULL, admin);
        assertThat(full.deactivated()).isEqualTo(1);
        assertThat(delisted.isActive()).isFalse();

        WatchlistEntry stillActive = entry("OFAC_SDN", "OLD2", "Other Person", "RU", EntityType.INDIVIDUAL);
        lenient().when(entryRepository.findByListSourceAndActiveTrue("OFAC_SDN")).thenReturn(List.of(stillActive));
        var delta = service.importCsv(HEADER + "OFAC_SDN,2,Jane Roe,,RU,INDIVIDUAL\n", WatchlistMode.DELTA, admin);
        assertThat(delta.deactivated()).isZero();
        assertThat(stillActive.isActive()).isTrue();
    }

    @Test
    void aFullImportOnlyTouchesTheListsItMentions() {
        service.importCsv(HEADER + "UN,1,Some Person,,,\n", WatchlistMode.FULL, admin);

        verify(entryRepository).findByListSourceAndActiveTrue("UN");
        verify(entryRepository, never()).findByListSourceAndActiveTrue("OFAC_SDN");
    }

    @Test
    void duplicateEntriesInOneFileAreRejected() {
        assertThatThrownBy(() -> service.importCsv(HEADER + "UN,1,Person One,,,\nUN,1,Person Uno,,,\n", WatchlistMode.FULL, admin))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate entry");
    }

    @Test
    void theNumberOfNewlyFlaggedCustomersIsReported() {
        when(screeningService.rescreenAll(any())).thenReturn(3);

        var result = service.importCsv(HEADER + "OFAC_SDN,1,Ivan Petrov,,RU,INDIVIDUAL\n", WatchlistMode.FULL, admin);

        assertThat(result.partiesFlagged()).isEqualTo(3);
    }
}
