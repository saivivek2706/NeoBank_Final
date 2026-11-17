package com.neobank.compliance.domain;

/** How a case ended. SAR_FILED = a suspicious activity report was submitted to the regulator (FIU). */
public enum Disposition {
    NO_SUSPICION,
    FALSE_POSITIVE,
    SAR_FILED,
    ACCOUNT_RESTRICTED
}
