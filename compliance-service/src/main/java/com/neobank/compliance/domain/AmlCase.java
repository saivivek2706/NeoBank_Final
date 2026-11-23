package com.neobank.compliance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An investigation: one or more alerts (or a sanctions hit) about one account, worked by a compliance officer.
 *
 * The state machine lives here so no caller can skip a step:
 *   OPEN -> IN_REVIEW (assigned) -> ESCALATED (needs a second person) -> CLOSED, or CLOSED straight from any open state.
 *
 * Maker-checker: whoever ESCALATES a case is recorded in {@code escalatedBy}; CaseService refuses to let that same
 * person approve the decisions that need four eyes (filing a SAR, clearing an auto-frozen sanctions hit).
 */
@Entity
@Table(name = "aml_cases")
@Setter(AccessLevel.PRIVATE)
public class AmlCase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 30)
    private String caseNumber;

    @Column(nullable = false)
    private UUID subjectAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CaseType caseType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CaseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity priority;

    private UUID assignee;

    private UUID escalatedBy;

    /** Compliance asked for this account to be frozen because of this case. */
    @Column(nullable = false)
    private boolean accountFrozen;

    @Column(nullable = false, length = 500)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private Disposition disposition;

    private UUID closedBy;

    @Column(nullable = false)
    private Instant openedAt;

    @Column(nullable = false)
    private Instant dueAt;

    private Instant closedAt;

    @Version
    private long version;

    /** Public no-argument constructor required by JPA/MongoDB. */
    public AmlCase() {
    }

    public UUID getId() {
        return this.id;
    }

    private void setId(UUID id) {
        this.id = id;
    }

    public String getCaseNumber() {
        return this.caseNumber;
    }

    public UUID getSubjectAccountId() {
        return this.subjectAccountId;
    }

    public CaseType getCaseType() {
        return this.caseType;
    }

    public CaseStatus getStatus() {
        return this.status;
    }

    public Severity getPriority() {
        return this.priority;
    }

    public UUID getAssignee() {
        return this.assignee;
    }

    public UUID getEscalatedBy() {
        return this.escalatedBy;
    }

    public boolean isAccountFrozen() {
        return this.accountFrozen;
    }

    public String getSummary() {
        return this.summary;
    }

    public Disposition getDisposition() {
        return this.disposition;
    }

    public UUID getClosedBy() {
        return this.closedBy;
    }

    public Instant getOpenedAt() {
        return this.openedAt;
    }

    public Instant getDueAt() {
        return this.dueAt;
    }

    public Instant getClosedAt() {
        return this.closedAt;
    }

    public long getVersion() {
        return this.version;
    }

    private void setVersion(long version) {
        this.version = version;
    }

    public static AmlCase open(String caseNumber, UUID subjectAccountId, CaseType type, Severity priority,
                               String summary, Instant openedAt, Instant dueAt) {
        AmlCase c = new AmlCase();
        c.setCaseNumber(caseNumber);
        c.setSubjectAccountId(subjectAccountId);
        c.setCaseType(type);
        c.setStatus(CaseStatus.OPEN);
        c.setPriority(priority);
        c.setSummary(summary);
        c.setOpenedAt(openedAt);
        c.setDueAt(dueAt);
        return c;
    }

    public boolean isClosed() {
        return status == CaseStatus.CLOSED;
    }

    /** Takes the case for review. An already-escalated case keeps its status (the approver is just being assigned). */
    public void assignTo(UUID newAssignee) {
        requireOpen();
        setAssignee(newAssignee);
        if (status == CaseStatus.OPEN) {
            setStatus(CaseStatus.IN_REVIEW);
        }
    }

    public void escalate(UUID by) {
        requireOpen();
        if (status == CaseStatus.ESCALATED) {
            throw new IllegalStateException("Case is already escalated");
        }
        setStatus(CaseStatus.ESCALATED);
        setEscalatedBy(by);
    }

    /** Raises (never lowers) the priority; the due date only ever moves earlier. Returns true if anything changed. */
    public boolean raisePriority(Severity newPriority, Instant candidateDueAt) {
        requireOpen();
        if (newPriority.ordinal() <= priority.ordinal()) {
            return false;
        }
        setPriority(newPriority);
        if (candidateDueAt.isBefore(dueAt)) {
            setDueAt(candidateDueAt);
        }
        return true;
    }

    public void markAccountFrozen() {
        setAccountFrozen(true);
    }

    public void close(UUID by, Disposition finalDisposition, Instant now) {
        requireOpen();
        setStatus(CaseStatus.CLOSED);
        setDisposition(finalDisposition);
        setClosedBy(by);
        setClosedAt(now);
    }

    public boolean isOverdue(Instant now) {
        return !isClosed() && dueAt.isBefore(now);
    }

    private void requireOpen() {
        if (isClosed()) {
            throw new IllegalStateException("Case " + caseNumber + " is closed");
        }
    }
}
