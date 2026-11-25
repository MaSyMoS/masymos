package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.SourceDocument;
import org.masymos.core.extract.SupportedSchemaVersions;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/** The matrix rules (CORP-4): a construct nobody produced is a gap unless a dated row explains it. */
class CorpusCoverageTest {

    private static final Map<String, Long> NO_LABELS = Map.of();
    private static final Map<String, Long> NO_RELATIONSHIPS = Map.of();
    private static final Map<String, Set<String>> NO_VERSIONS = Map.of();
    private static final Set<String> NO_UNVERSIONED = Set.of();
    private static final Map<String, Long> NO_STRATA = Map.of();
    private static final Map<String, String> NO_REDUCED_READS = Map.of();
    private static final Set<String> NO_EXTERNAL_CMETA = Set.of();
    private static final Set<String> NOTHING_EXPLAINED = Set.of();
    private static final List<CorpusCoverage.StoredCopy> NO_STORED_DOCUMENTS = List.of();

    /**
     * {@code CORP-16}'s fixture: one document cut at the default ceiling, one kept whole although it is
     * over that ceiling (a run may store under a raised one), and one that fits. The two counts differ on
     * purpose - a section that compared sizes instead of reading the flag would answer 2 truncated.
     */
    private static final List<CorpusCoverage.StoredCopy> STORED_DOCUMENTS = List.of(
            new CorpusCoverage.StoredCopy("corpus/cut", SourceDocument.DEFAULT_MAX_BYTES + 1L, true),
            new CorpusCoverage.StoredCopy("corpus/kept-whole", SourceDocument.DEFAULT_MAX_BYTES + 10L, false),
            new CorpusCoverage.StoredCopy("corpus/small", 1024L, false));

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

    /**
     * {@code T025-1}: the vocabulary the matrix iterates is the writer's whitelist, so an edge the
     * SED-ML mapper emits but the whitelist omits is invisible - neither covered nor a gap. The three
     * edges a repeated task writes were in exactly that hole: they reached the graph only through the
     * sanitized-qualifier fallback, which the matrix does not read.
     */
    @Test
    void everyEdgeTheSedmlMapperEmitsIsAGapWhenNoSampleProducesIt() {
        var gaps = CorpusCoverage.gaps(NO_LABELS, Map.of("HAS_CHANGE", 3L), NO_VERSIONS, NOTHING_EXPLAINED);

        assertTrue(
                gaps.stream().anyMatch(gap -> gap.contains("RELATIONSHIP HAS_RANGE")),
                "a range edge no sample produced must be a gap, not silence: " + gaps);
        assertTrue(
                gaps.stream().anyMatch(gap -> gap.contains("RELATIONSHIP HAS_SUBTASK")),
                "a sub-task edge no sample produced must be a gap, not silence: " + gaps);
        assertFalse(
                gaps.stream().anyMatch(gap -> gap.contains("RELATIONSHIP HAS_CHANGE")),
                "a change edge the sample did produce is not a gap: " + gaps);
    }

    @Test
    void theRelationshipTableNamesTheThreeEdgesARepeatedTaskWrites() {
        var report = CorpusCoverage.report(
                NO_LABELS,
                Map.of("HAS_CHANGE", 3L),
                NO_VERSIONS,
                NO_UNVERSIONED,
                NO_STRATA,
                NO_REDUCED_READS,
                NO_EXTERNAL_CMETA,
                Set.of("RELATIONSHIP HAS_RANGE", "RELATIONSHIP HAS_SUBTASK"),
                NO_STORED_DOCUMENTS);

        assertTrue(report.contains("| HAS_CHANGE |"), "the change edge the sample produced is counted: " + report);
        assertTrue(
                report.contains("| HAS_RANGE | 0 | explained |"),
                "a range edge no sample produced is named and explained, not missing: " + report);
        assertTrue(
                report.contains("| HAS_SUBTASK | 0 | explained |"),
                "a sub-task edge no sample produced is named and explained, not missing: " + report);
    }

    @Test
    void anUnproducedRelationshipIsAGap() {
        var gaps = CorpusCoverage.gaps(NO_LABELS, Map.of("HAS_SPECIES", 2L), NO_VERSIONS, NOTHING_EXPLAINED);

        assertTrue(gaps.stream().anyMatch(gap -> gap.contains("RELATIONSHIP HAS_UNIT")), gaps.toString());
    }

    @Test
    void anUnproducedVersionIsAGap() {
        var key = CorpusCoverage.versionKey(SupportedSchemaVersions.forFormat("SEDML").getLast());
        var gaps = CorpusCoverage.gaps(
                NO_LABELS, NO_RELATIONSHIPS, Map.of(key, Set.of("some/sedml")), NOTHING_EXPLAINED);

        assertFalse(gaps.stream().anyMatch(gap -> gap.contains(key)), gaps.toString());
        assertTrue(gaps.stream().anyMatch(gap -> gap.startsWith("VERSION SEDML 1 1")), gaps.toString());
    }

    @Test
    void theReportListsEveryVocabularyEntryAndTheGaps() {
        var report = CorpusCoverage.report(
                Map.of("SBML_SPECIES", 2L), Map.of("HAS_SPECIES", 2L), Map.of("SBML 2 4", Set.of("a/model")),
                NO_UNVERSIONED, Map.of("SBML|2|4|curated", 1L), NO_REDUCED_READS, NO_EXTERNAL_CMETA,
                NOTHING_EXPLAINED, NO_STORED_DOCUMENTS);

        assertTrue(report.contains("| SBML_SPECIES | 2 | yes |"), report);
        assertTrue(report.contains("| HAS_SPECIES | 2 | yes |"), report);
        assertTrue(report.contains("| SBML 2 4 | 1 | yes |"), report);
        assertTrue(report.contains("## Gaps"), report);
        assertTrue(report.contains("was never produced"), "an uncovered construct is named, not summarised");
    }

    /**
     * CORP-9: a reduced read is a caveat the matrix has to voice. Both directions are pinned, because
     * a section that always says "None" would pass a one-directional test while telling nothing.
     */
    @Test
    void theReportSaysNoneWhenEveryDocumentWasReadInOnePass() {
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, NO_VERSIONS, NO_UNVERSIONED, NO_STRATA, NO_REDUCED_READS,
                NO_EXTERNAL_CMETA, NOTHING_EXPLAINED, NO_STORED_DOCUMENTS);

        assertTrue(report.contains("## Reduced reads"), report);
        assertTrue(report.contains("None: every document was read in one pass."), report);
    }

    @Test
    void theReportNamesEverySampleTheRetryWasNeededFor() {
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, NO_VERSIONS, NO_UNVERSIONED, NO_STRATA,
                Map.of("BIOMD0000000899", "http://www.sbml.org/2001/ns/celldesigner"),
                NO_EXTERNAL_CMETA, NOTHING_EXPLAINED, NO_STORED_DOCUMENTS);

        assertTrue(report.contains("1 sample(s) needed the reduced read"), report);
        assertTrue(
                report.contains("| BIOMD0000000899 | http://www.sbml.org/2001/ns/celldesigner |"),
                report);
        assertFalse(report.contains("None: every document was read in one pass."), report);
    }

    /**
     * CORP-10: a harvested {@code @@cmeta} sidecar that no document read leaves no trace, so the
     * section must still say so - both directions are pinned, because a section that always names
     * documents would pass a test that only checks the non-empty case.
     */
    @Test
    void theReportSaysNoneWhenNoDocumentWasEnrichedFromASidecar() {
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, NO_VERSIONS, NO_UNVERSIONED, NO_STRATA, NO_REDUCED_READS,
                NO_EXTERNAL_CMETA, NOTHING_EXPLAINED, NO_STORED_DOCUMENTS);

        assertTrue(report.contains("## External cmeta"), report);
        assertTrue(
                report.contains("None: no sampled CellML document was enriched from a harvested @@cmeta sidecar."),
                report);
    }

    @Test
    void theReportNamesEveryDocumentEnrichedFromASidecar() {
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, NO_VERSIONS, NO_UNVERSIONED, NO_STRATA, NO_REDUCED_READS,
                Set.of("BIOMD0000000700"), NOTHING_EXPLAINED, NO_STORED_DOCUMENTS);

        assertTrue(report.contains("## External cmeta"), report);
        assertTrue(report.contains("1 document(s) were enriched from a harvested `@@cmeta` sidecar"), report);
        assertTrue(report.contains("| BIOMD0000000700 |"), report);
        assertFalse(
                report.contains("None: no sampled CellML document was enriched from a harvested @@cmeta sidecar."),
                report);
    }

    /**
     * CORP-11: a version the sample produced but the claim does not list used to disappear from the
     * version dimension - no row, no gap, no sentence. It is now named with the documents behind it.
     */
    @Test
    void anUnclaimedVersionIsReportedWithTheDocumentThatProducedIt() {
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, Map.of("SBML 2 1", Set.of("biomodels/BIOMD0000000073")),
                NO_UNVERSIONED, NO_STRATA, NO_REDUCED_READS, NO_EXTERNAL_CMETA, NOTHING_EXPLAINED,
                NO_STORED_DOCUMENTS);

        assertTrue(report.contains("## Versions outside the claim"), report);
        assertTrue(report.contains("| SBML 2 1 | 1 | biomodels/BIOMD0000000073 |"), report);
    }

    /** CORP-11, the other direction: a version inside the claim is not called out as excess. */
    @Test
    void aClaimedVersionIsNotCalledOutAsUnclaimed() {
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, Map.of("SBML 2 4", Set.of("biomodels/BIOMD0000000001")),
                NO_UNVERSIONED, NO_STRATA, NO_REDUCED_READS, NO_EXTERNAL_CMETA, NOTHING_EXPLAINED,
                NO_STORED_DOCUMENTS);

        assertTrue(report.contains("## Versions outside the claim"), report);
        assertTrue(report.contains("None: every document states a version this project claims."), report);
        assertFalse(report.contains("| SBML 2 4 | 1 | biomodels/BIOMD0000000001 |"), report);
    }

    /**
     * CORP-12: the count is taken from the document, not from the manifest row - so an archive's
     * member contributes its own format and version instead of `OMEX - -`.
     */
    @Test
    void anArchivedMemberStatesItsOwnVersion() throws IOException {
        var stated = CorpusCoverage.statedVersions(CorpusDocuments.ofArchive("jjj/demo", twoMemberArchive()).stream()
                .map(CorpusDocuments.ReadDocument::model)
                .toList());

        assertEquals(Set.of("SBML 3 1", "SEDML 1 3"), stated.byVersion().keySet(), stated.toString());
        assertTrue(stated.unversioned().isEmpty(), stated.toString());
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, stated.byVersion(), stated.unversioned(), NO_STRATA,
                NO_REDUCED_READS, NO_EXTERNAL_CMETA, NOTHING_EXPLAINED, NO_STORED_DOCUMENTS);
        assertTrue(report.contains("| SBML 3 1 | 1 | yes |"), report);
        assertTrue(report.contains("| SEDML 1 3 | 1 | yes |"), report);
        assertFalse(report.contains("OMEX"), "an archive's own label must not stand in for its members: " + report);
    }

    /** The same silence CORP-11 closed for excess versions: a document that states none is named. */
    @Test
    void aDocumentWithoutAStatedVersionIsNamed() {
        var document = new ParsedModel(
                new ModelReference("demo/quiet", URI.create("urn:masymos:corpus:demo/quiet"), ModelType.SBML),
                "quiet", "quiet", Map.of());

        var stated = CorpusCoverage.statedVersions(List.of(document));
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, stated.byVersion(), stated.unversioned(), NO_STRATA,
                NO_REDUCED_READS, NO_EXTERNAL_CMETA, NOTHING_EXPLAINED, NO_STORED_DOCUMENTS);

        assertEquals(Set.of("demo/quiet"), stated.unversioned(), stated.toString());
        assertTrue(report.contains("1 document(s) state no schema version of their own"), report);
        assertTrue(report.contains("demo/quiet"), report);
    }

    /**
     * {@code CORP-16}: the manifest holds documents above the storage ceiling, so the write path keeps a
     * shorter copy of them - and no report line said so, which made the one artifact the lane produces
     * silent about its own fidelity. The count is read from the storage path's flag, so the document
     * kept whole under a raised ceiling is over the default ceiling without being counted as cut.
     */
    @Test
    void countsATruncatedDocumentAsTruncated() {
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, NO_VERSIONS, NO_UNVERSIONED, NO_STRATA, NO_REDUCED_READS,
                NO_EXTERNAL_CMETA, NOTHING_EXPLAINED, STORED_DOCUMENTS);

        assertTrue(report.contains("## Stored documents"), report);
        assertTrue(report.contains("| documents | 3 |"), report);
        assertTrue(report.contains("| truncated | 1 |"), report);
        assertTrue(report.contains("| documents over the ceiling | 2 |"), report);
        assertFalse(report.contains("None: no document was measured against the storage ceiling"), report);
    }

    /**
     * The maximum is the largest original, not the largest stored copy: a truncated document's stored
     * copy is shorter than what {@code documentBytes} reports, so measuring the stored length would
     * understate the sample it came from.
     */
    @Test
    void reportsTheLargestStoredDocument() {
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, NO_VERSIONS, NO_UNVERSIONED, NO_STRATA, NO_REDUCED_READS,
                NO_EXTERNAL_CMETA, NOTHING_EXPLAINED, STORED_DOCUMENTS);

        assertTrue(report.contains("| largest bytes | " + (SourceDocument.DEFAULT_MAX_BYTES + 10) + " |"), report);
        assertTrue(report.contains("corpus/kept-whole"), report);
    }

    /** The other direction, as every caveat section pins it: nothing measured is not a silent section. */
    @Test
    void theReportSaysNoneWhenNoDocumentWasMeasured() {
        var report = CorpusCoverage.report(
                NO_LABELS, NO_RELATIONSHIPS, NO_VERSIONS, NO_UNVERSIONED, NO_STRATA, NO_REDUCED_READS,
                NO_EXTERNAL_CMETA, NOTHING_EXPLAINED, NO_STORED_DOCUMENTS);

        assertTrue(report.contains("## Stored documents"), report);
        assertTrue(report.contains("None: no document was measured against the storage ceiling"), report);
    }

    /** One SBML L3V1 member and one SED-ML L1V3 member, declared by the archive's own manifest. */
    private static byte[] twoMemberArchive() throws IOException {
        var manifest = """
                <?xml version="1.0" encoding="UTF-8"?>
                <omexManifest xmlns="http://identifiers.org/combine.specifications/omex-manifest">
                  <content location="models/a.sbml" format="http://identifiers.org/combine.specifications/sbml.level-3.version-1"/>
                  <content location="sedml/a.sedml" format="http://identifiers.org/combine.specifications/sed-ml.level-1.version-3" master="true"/>
                </omexManifest>
                """;
        var sbml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sbml xmlns="http://www.sbml.org/sbml/level3/version1/core" level="3" version="1">
                  <model id="demo"/>
                </sbml>
                """;
        var sedml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sedML xmlns="http://sed-ml.org/sed-ml/level1/version3" level="1" version="3"/>
                """;
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            put(zip, "manifest.xml", manifest);
            put(zip, "models/a.sbml", sbml);
            put(zip, "sedml/a.sedml", sedml);
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
