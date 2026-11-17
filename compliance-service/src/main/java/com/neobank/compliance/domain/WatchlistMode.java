package com.neobank.compliance.domain;

/** FULL = the file is a complete snapshot of its list(s): entries no longer present are deactivated. DELTA only adds/updates. */
public enum WatchlistMode {
    FULL,
    DELTA
}
