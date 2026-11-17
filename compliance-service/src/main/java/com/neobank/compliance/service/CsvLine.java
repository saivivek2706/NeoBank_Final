package com.neobank.compliance.service;

import java.util.ArrayList;
import java.util.List;

/** Minimal CSV field splitter: commas separate fields, double quotes group (and "" is a literal quote). */
final class CsvLine {

    private CsvLine() {
    }

    static List<String> split(String row) {
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
