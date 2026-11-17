package com.neobank.compliance.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameMatcherTest {

    private static double score(String a, String b) {
        return NameMatcher.score(NameNormalizer.normalize(a), NameNormalizer.normalize(b));
    }

    // ---- normalisation

    @Test
    void normalisationIgnoresCaseAccentsPunctuationAndSpacing() {
        assertThat(NameNormalizer.normalize("  José  O'Brien-Smith ")).isEqualTo("jose o brien smith");
        assertThat(NameNormalizer.normalize("MÜLLER, Hans")).isEqualTo("muller hans");
        assertThat(NameNormalizer.normalize(null)).isEmpty();
        assertThat(NameNormalizer.normalize("!!!")).isEmpty();
    }

    @Test
    void normalisationKeepsNonLatinScripts() {
        assertThat(NameNormalizer.normalize("王 伟")).isEqualTo("王 伟");
        assertThat(NameNormalizer.normalize("محمد")).isEqualTo("محمد");
    }

    @Test
    void sortedTokensMakeWordOrderIrrelevant() {
        assertThat(NameNormalizer.sortedTokens("smith john")).isEqualTo(NameNormalizer.sortedTokens("john smith"));
        assertThat(NameNormalizer.sortedTokens("")).isEmpty();
    }

    // ---- scoring tiers

    @Test
    void identicalNamesScoreExactlyOne_regardlessOfCaseAccentsAndOrder() {
        assertThat(score("Ivan Petrov", "ivan petrov")).isEqualTo(1.0);
        assertThat(score("Petrov, Ivan", "Ivan PETROV")).isEqualTo(1.0);
        assertThat(score("José García", "Jose Garcia")).isEqualTo(1.0);
        assertThat(score("María García López", "Maria Lopez Garcia")).isEqualTo(1.0);
    }

    @Test
    void closeVariantsScoreHighButNeverReachTheAutoFreezeTier() {
        for (String[] pair : new String[][]{
                {"John Smith", "John Smithson"}, {"Jon Smith", "John Smith"}, {"Viktor Bout", "Victor Bout"},
                {"Mohamed Al Rashid", "Mohammed Alrashid"}, {"Ahmad Khalil", "Ahmed Khalil"}}) {
            double s = score(pair[0], pair[1]);
            assertThat(s).as(pair[0] + " vs " + pair[1]).isGreaterThanOrEqualTo(0.88).isLessThan(1.0);
        }
    }

    @Test
    void fuzzyScoresAreCappedSoOnlyAnIdenticalNameCanBeAStrongMatch() {
        assertThat(score("Sanctioned Person", "Sanctioned Persons")).isEqualTo(NameMatcher.FUZZY_CAP);
    }

    @Test
    void unrelatedNamesScoreLow() {
        assertThat(score("John Smith", "Jane Doe")).isLessThan(0.7);
        assertThat(score("Alice Tan", "Robert Johnson")).isLessThan(0.7);
    }

    @Test
    void aSharedFirstNameAloneIsNotEnough() {
        assertThat(score("Ivan Petrov", "Ivan Sidorov")).isLessThan(0.88);
    }

    @Test
    void veryShortNamesOnlyMatchExactly() {
        assertThat(score("Li", "Lin")).isZero();
        assertThat(score("Li", "Li")).isEqualTo(1.0);
    }

    @Test
    void emptyInputNeverMatches() {
        assertThat(NameMatcher.score("", "ivan petrov")).isZero();
        assertThat(NameMatcher.score("ivan petrov", "")).isZero();
    }

    @Test
    void jaroWinklerKnownValues() {
        assertThat(NameMatcher.jaroWinkler("martha", "marhta")).isBetween(0.96, 0.962);
        assertThat(NameMatcher.jaroWinkler("abc", "xyz")).isZero();
        assertThat(NameMatcher.jaroWinkler("same", "same")).isEqualTo(1.0);
    }
}
