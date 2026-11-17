package com.neobank.compliance.service;

/**
 * Scores how alike two (already normalized) names are, from 0.0 to 1.0.
 *
 *   1.00  identical after normalisation, ignoring word order  -> a STRONG match (auto-freeze territory)
 *   <=0.96 anything fuzzy (typos, transliteration variants, a missing middle name) - deliberately CAPPED below 1.0 so a
 *          close-but-not-identical name can only ever go to a human reviewer, never trigger an automatic freeze.
 *
 * Fuzzy score = the best of Jaro-Winkler over the names as written and over their sorted tokens, plus a per-token
 * average when both names have the same number of words (catches "Mohamed Al Rashid" vs "Mohammed Alrashid" style drift).
 */
public final class NameMatcher {

    public static final double FUZZY_CAP = 0.96;
    private static final int MIN_FUZZY_LENGTH = 4;
    private static final double MIN_TOKEN_SIMILARITY = 0.85;

    private NameMatcher() {
    }

    public static double score(String normalizedA, String normalizedB) {
        if (normalizedA.isEmpty() || normalizedB.isEmpty()) {
            return 0.0;
        }
        if (normalizedA.equals(normalizedB)) {
            return 1.0;
        }
        String sortedA = NameNormalizer.sortedTokens(normalizedA);
        String sortedB = NameNormalizer.sortedTokens(normalizedB);
        if (sortedA.equals(sortedB)) {
            return 1.0;
        }
        // Very short names produce wildly unreliable fuzzy scores ("Li" vs "Lin"): only an exact match counts.
        if (normalizedA.length() < MIN_FUZZY_LENGTH || normalizedB.length() < MIN_FUZZY_LENGTH) {
            return 0.0;
        }
        double best = Math.max(jaroWinkler(normalizedA, normalizedB), jaroWinkler(sortedA, sortedB));
        best = Math.max(best, tokenAverage(sortedA, sortedB));
        return Math.min(best, FUZZY_CAP);
    }

    /** Average of each token's best match in the other name; only meaningful when both names have as many words. */
    private static double tokenAverage(String sortedA, String sortedB) {
        String[] a = sortedA.split(" ");
        String[] b = sortedB.split(" ");
        if (a.length != b.length || a.length < 2) {
            return 0.0;
        }
        double sum = 0;
        for (String tokenA : a) {
            double bestForToken = 0;
            for (String tokenB : b) {
                bestForToken = Math.max(bestForToken, jaroWinkler(tokenA, tokenB));
            }
            if (bestForToken < MIN_TOKEN_SIMILARITY) {
                return 0.0;   // one word is clearly different: do not let the others carry the match
            }
            sum += bestForToken;
        }
        return sum / a.length;
    }

    /** Standard Jaro-Winkler similarity (prefix scale 0.1, prefix capped at 4). */
    static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }
        int matchDistance = Math.max(0, Math.max(len1, len2) / 2 - 1);
        boolean[] matched1 = new boolean[len1];
        boolean[] matched2 = new boolean[len2];

        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int from = Math.max(0, i - matchDistance);
            int to = Math.min(i + matchDistance + 1, len2);
            for (int j = from; j < to; j++) {
                if (!matched2[j] && s1.charAt(i) == s2.charAt(j)) {
                    matched1[i] = true;
                    matched2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!matched1[i]) {
                continue;
            }
            while (!matched2[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }
        double m = matches;
        double jaro = (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0;

        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }
}
