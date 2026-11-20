package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.SupportedSchemaVersions;

/** The matrix rules (CORP-4): a construct nobody produced is a gap unless a dated row explains it. */
class CorpusCoverageTest {

    private static final Map<String, Long> NO_LABELS = Map.of();
    private static final Map<String, Long> NO_RELATIONSHIPS = Map.of();
    private static final Map<String, Long> NO_VERSIONS = Map.of();
    private static final Set<String> NOTHING_EXPLAINED = Set.of();

    @Test
    void everyUnproducedEntityTypeIsAGap() {
        var gaps = CorpusCoverage.gaps(
                Map.of("SBML_SPECIES", 3L), NO_RELATIONSHIPS, NO_VERSIONS, NOTHING_EXPLAINED);

        assertTrue(gaps.stream().anyMatch(gap -> gap.contains("SBML_REACTION")), gaps.toString());
        assertFalse(gaps.stream().anyMatch(gap -> gap.equals("LABEL SBML_SPECIES was never produced by any sample")),
                gaps.toString());
    }

    @Test
    void aDatedRowExplainsAGapButDoesNotHideAnother() {
        var gaps = CorpusCoverage.gaps(
                Map.of("SBML_SPECIES", 1L), NO_RELATIONSHIPS, NO_VERSIONS,
                Set.of("LABEL SBML_REACTION"));

        assertFalse(gaps.stream().anyMatch(gap -> gap.contains("SBML_REACTION")), gaps.toString());
        assertTrue(gaps.stream().anyMatch(gap -> gap.contains("SBML_COMPARTMENT")), gaps.toString());
    }

    @Test
    void anUnproducedRelationshipIsAGap() {
        var gaps = CorpusCoverage.gaps(NO_LABELS, Map.of("HAS_SPECIES", 2L), NO_VERSIONS, NOTHING_EXPLAINED);

        assertTrue(gaps.stream().anyMatch(gap -> gap.contains("RELATIONSHIP HAS_UNIT")), gaps.toString());
    }

    @Test
    void anUnproducedVersionIsAGap() {
        var key = CorpusCoverage.versionKey(SupportedSchemaVersions.forFormat("SEDML").getLast());
        var gaps = CorpusCoverage.gaps(NO_LABELS, NO_RELATIONSHIPS, Map.of(key, 1L), NOTHING_EXPLAINED);

        assertFalse(gaps.stream().anyMatch(gap -> gap.contains(key)), gaps.toString());
        assertTrue(gaps.stream().anyMatch(gap -> gap.startsWith("VERSION SEDML 1 1")), gaps.toString());
    }

    @Test
    void theReportListsEveryVocabularyEntryAndTheGaps() {
        var report = CorpusCoverage.report(
                Map.of("SBML_SPECIES", 2L), Map.of("HAS_SPECIES", 2L), Map.of("SBML 2 4", 1L),
                Map.of("SBML|2|4|curated", 1L), NOTHING_EXPLAINED);

        assertTrue(report.contains("| SBML_SPECIES | 2 | yes |"), report);
        assertTrue(report.contains("| HAS_SPECIES | 2 | yes |"), report);
        assertTrue(report.contains("| SBML 2 4 | 1 | yes |"), report);
        assertTrue(report.contains("## Gaps"), report);
        assertTrue(report.contains("was never produced"), "an uncovered construct is named, not summarised");
    }
}
