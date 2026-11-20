package org.masymos.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexTextTest {
    @Test
    void expandsUnderscoreIdentifierAdditively() {
        assertEquals("species_1 species", IndexText.expandTermsSpecialChars("species_1"));
    }

    @Test
    void keepsTheEmailAndAddsItsParts() {
        assertEquals(
                "jane@example.org jane example org",
                IndexText.expandTermsSpecialChars("jane@example.org")
        );
    }

    @Test
    void keepsTheFileNameAndAddsItsParts() {
        assertEquals("model.xml model xml", IndexText.expandTermsSpecialChars("model.xml"));
    }

    @Test
    void splitsCamelCaseAdditively() {
        assertEquals("hasPart has part", IndexText.expandTermsSpecialChars("hasPart"));
    }

    @Test
    void splitsAnIdentifierIntoLettersAndDigits() {
        assertEquals("BIOMD0000000001 biomd 0000000001", IndexText.expandTermsSpecialChars("BIOMD0000000001"));
    }

    @Test
    void expandsEveryTokenAndDeduplicatesTheFragments() {
        var value = "environment/time time environment/clock clock";

        var expanded = IndexText.expandTermsSpecialChars(value);

        assertEquals(value + " environment", expanded);
        assertEquals(List.of("environment"), IndexText.termFragments(value));
    }

    @Test
    void keepsValuesBelowTheLegacyLengthPrecondition() {
        assertSame("a_b", IndexText.expandTermsSpecialChars("a_b"));
        assertEquals(List.of(), IndexText.termFragments("a_b"));
    }

    @Test
    void keepsValuesWithoutSeparatorOrBoundary() {
        assertSame("plain words", IndexText.expandTermsSpecialChars("plain words"));
        assertEquals(List.of(), IndexText.termFragments("plain words"));
    }

    @Test
    void keepsNullAndBlank() {
        assertSame(null, IndexText.expandTermsSpecialChars(null));
        assertSame("   ", IndexText.expandTermsSpecialChars("   "));
        assertSame(null, IndexText.shrinkToMaxTermLength(null));
        assertEquals(List.of(), IndexText.termFragments(null));
    }

    @Test
    void dropsFragmentsShorterThanTwoCharacters() {
        assertSame("r_1 x", IndexText.expandTermsSpecialChars("r_1 x"));
        assertEquals(List.of("biomd"), IndexText.termFragments("BIOMD1"));
    }

    @Test
    void truncatesOversizedTextBelowTheLegacyCeiling() {
        var oversized = "a".repeat(40000);

        var shrunk = IndexText.shrinkToMaxTermLength(oversized);

        assertTrue(shrunk.getBytes(StandardCharsets.UTF_8).length <= IndexText.LEGACY_TERM_BYTE_LIMIT - 1);
        assertTrue(shrunk.length() < oversized.length());
    }

    @Test
    void leavesTextBelowTheCeilingUntouched() {
        var value = "b".repeat(1000);

        assertSame(value, IndexText.shrinkToMaxTermLength(value));
    }

    @Test
    void stripsNonAsciiCharactersWhenShrinking() {
        var oversized = "\u00e4".repeat(20000);

        var shrunk = IndexText.shrinkToMaxTermLength(oversized);

        assertTrue(shrunk.getBytes(StandardCharsets.UTF_8).length <= IndexText.LEGACY_TERM_BYTE_LIMIT - 1);
        assertTrue(shrunk.chars().allMatch(character -> character < 0x80));
    }
}
