package com.neobank.compliance.service;

import com.neobank.compliance.domain.CaseType;
import com.neobank.compliance.domain.Disposition;

import java.util.UUID;

/**
 * Published (in-process, inside the closing transaction) when a case is closed, so side effects such as resolving a
 * sanctions hit live with the component that owns them instead of in CaseService.
 */
public record CaseClosedEvent(UUID caseId, UUID subjectAccountId, CaseType caseType, Disposition disposition,
                              UUID actor, String note) {
}
