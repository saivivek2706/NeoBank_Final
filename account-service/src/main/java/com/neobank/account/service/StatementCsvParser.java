package com.neobank.account.service;

import com.neobank.account.domain.MovementDirection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the settlement bank's daily statement CSV:
 *
 *   reference,direction,amount,currency[,description]
 *   BANK-REF-001,INFLOW,500.00,SGD,Incoming FAST payment
 *
 * `direction` is from the bank's cash point of view (INFLOW = money received, OUTFLOW = money paid out).
 * Double-quoted fields may contain commas. Errors name the offending line so the file can be fixed and re-uploaded.
 */
public final class StatementCsvParser {

    private StatementCsvParser() {
    }

    public static List<StatementReconciliationService.LineInput> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("Statement CSV is empty");
        }
        String[] rows = csv.split("\\r?\\n");
        List<String> header = split(rows[0]);
        if (header.size() < 4 || !header.get(0).equalsIgnoreCase("reference") || !header.get(1).equalsIgnoreCase("direction")
                || !header.get(2).equalsIgnoreCase("amount") || !header.get(3).equalsIgnoreCase("currency")) {
            throw new IllegalArgumentException("Line 1: header must start with reference,direction,amount,currency");
        }

        List<StatementReconciliationService.LineInput> lines = new ArrayList<>();
        for (int i = 1; i < rows.length; i++) {
            if (rows[i].isBlank()) {
                continue;
            }
            int lineNo = i + 1;
            List<String> f = split(rows[i]);
            if (f.size() < 4) {
                throw new IllegalArgumentException("Line " + lineNo + ": expected at least 4 columns, got " + f.size());
            }
            String reference = f.get(0).trim();
            if (reference.isEmpty()) {
                throw new IllegalArgumentException("Line " + lineNo + ": reference is empty");
            }
            MovementDirection direction;
            try {
                direction = MovementDirection.valueOf(f.get(1).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + lineNo + ": direction must be INFLOW or OUTFLOW");
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(f.get(2).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Line " + lineNo + ": amount is not a number");
            }
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Line " + lineNo + ": amount must be positive (use direction for the sign)");
            }
            String description = f.size() > 4 ? f.get(4).trim() : null;
            lines.add(new StatementReconciliationService.LineInput(reference, direction, amount,
                    f.get(3).trim().toUpperCase(Locale.ROOT), description));
        }
        return lines;
    }

    private static List<String> split(String row) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
