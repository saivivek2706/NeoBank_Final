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

/** Append-only case history (enforced by a database trigger): the audit trail of every decision. */
@Entity
@Table(name = "case_events")
@Getter
@Setter(AccessLevel.PRIVATE)
@NoArgsConstructor
public class CaseEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CaseEventType eventType;

    /** The user who acted; null means the system (an automatic rule or screening). */
    private UUID actor;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CaseStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CaseStatus toStatus;

    @Column(length = 1000)
    private String note;

    @Column(nullable = false)
    private Instant createdAt;

    public static CaseEvent of(UUID caseId, CaseEventType type, UUID actor, CaseStatus from, CaseStatus to, String note) {
        CaseEvent e = new CaseEvent();
        e.setCaseId(caseId);
        e.setEventType(type);
        e.setActor(actor);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setNote(note);
        e.setCreatedAt(Instant.now());
        return e;
    }

}
