package com.neobank.compliance.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical form used for every name comparison, so "José  O'Brien-Smith" and "jose o brien smith" are the same string.
 * Strips diacritics, lower-cases, and turns any run of non-letter/digit characters into a single space. Letters of every
 * script are kept (a Chinese or Arabic name is not reduced to nothing).
 */
public final class NameNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    private NameNormalizer() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        String withoutMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        return NON_ALPHANUMERIC.matcher(withoutMarks.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** Tokens of an already-normalized name sorted alphabetically: makes "smith john" and "john smith" identical. */
    public static String sortedTokens(String normalized) {
        if (normalized.isEmpty()) {
            return "";
        }
        String[] tokens = normalized.split(" ");
        Arrays.sort(tokens);
        return String.join(" ", tokens);
    }
}
