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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One immutable record per screening run - the evidence of what was checked, against which list, with what result. */
@Entity
@Table(name = "screening_results")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class ScreeningResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID partyId;

    @Column(nullable = false, length = 255)
    private String screenedName;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private ScreeningTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScreeningOutcome outcome;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal topScore;

    /** JSON array of the matches found (entry id, list, matched name, via primary/alias, score). */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String matches;

    private UUID watchlistVersion;

    @Column(nullable = false)
    private Instant screenedAt;

    public static ScreeningResult of(UUID partyId, String screenedName, ScreeningTrigger trigger, ScreeningOutcome outcome,
                                     BigDecimal topScore, String matchesJson, UUID watchlistVersion) {
        ScreeningResult r = new ScreeningResult();
        r.setPartyId(partyId);
        r.setScreenedName(screenedName);
        r.setTrigger(trigger);
        r.setOutcome(outcome);
        r.setTopScore(topScore);
        r.setMatches(matchesJson);
        r.setWatchlistVersion(watchlistVersion);
        r.setScreenedAt(Instant.now());
        return r;
    }

}
