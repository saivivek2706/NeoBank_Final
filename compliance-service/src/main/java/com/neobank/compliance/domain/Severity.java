package com.neobank.compliance.domain;

/** Alert severity and, for a case, its priority. Declared in ascending order so `ordinal()` comparison means 'more severe'. */
public enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
