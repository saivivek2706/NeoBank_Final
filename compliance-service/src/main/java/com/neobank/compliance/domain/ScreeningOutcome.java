package com.neobank.compliance.domain;

/** STRONG_MATCH = the names are identical after normalisation; POTENTIAL_MATCH = a close fuzzy match a human must review. */
public enum ScreeningOutcome {
    CLEAR,
    POTENTIAL_MATCH,
    STRONG_MATCH
}
