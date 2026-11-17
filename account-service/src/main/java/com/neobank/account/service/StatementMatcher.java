package com.neobank.account.service;

import com.neobank.account.domain.BreakType;
import com.neobank.account.domain.MovementDirection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure matching of an external bank statement against the ledger's own record of the same movements, keyed by
 * bank reference. Free of any I/O so every rule is unit-testable.
 *
 * Every difference becomes a break; nothing is silently "close enough". A reference that appears more than once on
 * either side is reported as a duplicate rather than guessed at.
 */
public final class StatementMatcher {

    private StatementMatcher() {
    }

    public record Entry(String reference, MovementDirection direction, BigDecimal amount, String currency) {
    }

    public record Difference(BreakType type, String reference, BigDecimal statementAmount, BigDecimal ledgerAmount,
                             String detail) {
    }

    public record Result(int matched, List<Difference> differences) {
    }

    public static Result match(List<Entry> statement, List<Entry> ledger) {
        Map<String, List<Entry>> statementByRef = group(statement);
        Map<String, List<Entry>> ledgerByRef = group(ledger);

        List<Difference> differences = new ArrayList<>();
        Set<String> handled = new HashSet<>();
        int matched = 0;

        for (Map.Entry<String, List<Entry>> s : statementByRef.entrySet()) {
            String ref = s.getKey();
            handled.add(ref);
            List<Entry> onStatement = s.getValue();
            List<Entry> inLedger = ledgerByRef.get(ref);

            if (onStatement.size() > 1) {
                differences.add(new Difference(BreakType.DUPLICATE_REFERENCE_IN_STATEMENT, ref,
                        onStatement.get(0).amount(), inLedger == null ? null : inLedger.get(0).amount(),
                        "Reference appears " + onStatement.size() + " times on the bank statement"));
                if (inLedger != null && inLedger.size() > 1) {
                    differences.add(duplicateInLedger(ref, inLedger));
                }
                continue;
            }
            if (inLedger == null) {
                Entry line = onStatement.get(0);
                differences.add(new Difference(BreakType.MISSING_IN_LEDGER, ref, line.amount(), null,
                        "Bank statement shows a " + line.direction() + " of " + line.amount() + " " + line.currency()
                                + " that the ledger has no record of"));
                continue;
            }
            if (inLedger.size() > 1) {
                differences.add(duplicateInLedger(ref, inLedger));
                continue;
            }

            Entry bank = onStatement.get(0);
            Entry book = inLedger.get(0);
            if (!bank.currency().equals(book.currency())) {
                differences.add(new Difference(BreakType.CURRENCY_MISMATCH, ref, bank.amount(), book.amount(),
                        "Currency differs: bank " + bank.currency() + ", ledger " + book.currency()));
            } else if (bank.direction() != book.direction()) {
                differences.add(new Difference(BreakType.DIRECTION_MISMATCH, ref, bank.amount(), book.amount(),
                        "Direction differs: bank " + bank.direction() + ", ledger " + book.direction()));
            } else if (bank.amount().compareTo(book.amount()) != 0) {
                differences.add(new Difference(BreakType.AMOUNT_MISMATCH, ref, bank.amount(), book.amount(),
                        "Amount differs: bank " + bank.amount() + ", ledger " + book.amount()));
            } else {
                matched++;
            }
        }

        for (Map.Entry<String, List<Entry>> l : ledgerByRef.entrySet()) {
            if (handled.contains(l.getKey())) {
                continue;
            }
            Entry book = l.getValue().get(0);
            differences.add(new Difference(BreakType.MISSING_IN_STATEMENT, l.getKey(), null, book.amount(),
                    "Ledger recorded a " + book.direction() + " of " + book.amount() + " " + book.currency()
                            + " that the bank statement does not show"));
        }
        return new Result(matched, differences);
    }

    private static Difference duplicateInLedger(String ref, List<Entry> inLedger) {
        return new Difference(BreakType.DUPLICATE_REFERENCE_IN_LEDGER, ref, null, inLedger.get(0).amount(),
                "Reference appears " + inLedger.size() + " times in the ledger");
    }

    private static Map<String, List<Entry>> group(List<Entry> entries) {
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        for (Entry entry : entries) {
            grouped.computeIfAbsent(entry.reference(), k -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }
}
