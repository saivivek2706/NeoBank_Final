package com.neobank.compliance.domain;

/** OPEN -> IN_REVIEW (assigned) -> ESCALATED (second pair of eyes needed) -> CLOSED. */
public enum CaseStatus {
    OPEN,
    IN_REVIEW,
    ESCALATED,
    CLOSED
}
