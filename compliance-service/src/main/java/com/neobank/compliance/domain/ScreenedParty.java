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

@Entity
@Table(name = "screened_parties")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class ScreenedParty {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID accountId;

    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 255)
    private String partyName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PartyStatus status;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal topScore;

    /** True when compliance itself froze the account because of this hit (so only compliance may unfreeze it). */
    @Column(nullable = false)
    private boolean autoFrozen;

    @Column(nullable = false)
    private Instant lastScreenedAt;

    @Column(nullable = false)
    private Instant createdAt;

    private UUID resolvedBy;

    private Instant resolvedAt;

    @Column(length = 500)
    private String resolutionNote;

    public static ScreenedParty of(UUID accountId, UUID ownerId, String partyName) {
        ScreenedParty p = new ScreenedParty();
        p.setAccountId(accountId);
        p.setOwnerId(ownerId);
        p.setPartyName(partyName);
        p.setStatus(PartyStatus.CLEAR);
        p.setTopScore(BigDecimal.ZERO);
        p.setLastScreenedAt(Instant.now());
        p.setCreatedAt(Instant.now());
        return p;
    }

    public void recordScreening(BigDecimal topScore) {
        setLastScreenedAt(Instant.now());
        if (topScore.compareTo(getTopScore()) > 0) {
            setTopScore(topScore);
        }
    }

    public void flag(BigDecimal topScore, boolean frozen) {
        setStatus(PartyStatus.POTENTIAL_MATCH);
        setTopScore(topScore);
        setAutoFrozen(frozen);
        setLastScreenedAt(Instant.now());
    }

    public void confirmMatch(UUID by, String note, boolean frozen) {
        setStatus(PartyStatus.CONFIRMED_MATCH);
        setAutoFrozen(frozen);
        resolve(by, note);
    }

    public void clearAsFalsePositive(UUID by, String note) {
        setStatus(PartyStatus.CLEARED_FALSE_POSITIVE);
        setAutoFrozen(false);
        resolve(by, note);
    }

    private void resolve(UUID by, String note) {
        setResolvedBy(by);
        setResolvedAt(Instant.now());
        setResolutionNote(note);
    }

}
