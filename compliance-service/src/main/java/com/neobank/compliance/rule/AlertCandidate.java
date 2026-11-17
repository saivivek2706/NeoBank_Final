package com.neobank.compliance.rule;

import com.neobank.compliance.domain.Severity;

import java.util.Map;

/**
 * A rule's finding. `dedupKey` makes it idempotent in time: it names the rule, the account and the time bucket, so a burst
 * of qualifying transactions yields ONE alert rather than one per transaction.
 */
public record AlertCandidate(String ruleCode, Severity severity, int score, String summary,
                             Map<String, Object> details, String dedupKey) {
}
