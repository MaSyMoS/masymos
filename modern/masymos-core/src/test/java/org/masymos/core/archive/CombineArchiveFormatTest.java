package org.masymos.core.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.masymos.core.archive.CombineArchiveFormat.ArchiveEntryKind;

/**
 * The format a manifest declares is a specification <em>token</em>, not a substring: an entry whose
 * URI merely mentions {@code sbml} is not an SBML file, and a near-miss must stay visible as
 * {@code OTHER} together with the raw URI that produced it.
 */
class CombineArchiveFormatTest {

    @Test
    void anUnknownFormatTokenIsOtherAndTheRawUriIsKept() {
        var uri = "http://identifiers.org/combine.specifications/sbmlx";

        var format = CombineArchiveFormat.parse(uri);

        assertEquals(ArchiveEntryKind.OTHER, format.kind());
        assertEquals(uri, format.formatUri(), "the raw URI is what an operator needs to see");
    }

    @Test
    void aTokenThatMerelyContainsSbmlIsNotSbml() {
        assertEquals(ArchiveEntryKind.OTHER,
                CombineArchiveFormat.parse("./combine.specifications/sbml-notes").kind());
        assertEquals(ArchiveEntryKind.OTHER,
                CombineArchiveFormat.parse("http://identifiers.org/combine.specifications/sbml.docs").kind());
        assertEquals(ArchiveEntryKind.OTHER,
                CombineArchiveFormat.parse("http://identifiers.org/combine.specifications/omex-manifest").kind());
    }

    /** The spellings the corpus and the fixtures actually carry must keep decoding as they did. */
    @Test
    void theSpecificationTokensAndTheirVersionNotationStillDecode() {
        assertEquals(ArchiveEntryKind.SBML, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/sbml.level-2.version-4").kind());
        assertEquals(ArchiveEntryKind.SBML, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/sbml").kind());
        // cellml carries its version as bare numbers rather than level-/version- segments
        assertEquals(ArchiveEntryKind.CELLML, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/cellml.1.0").kind());
        assertEquals(ArchiveEntryKind.CELLML, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/cellml").kind());
        assertEquals(ArchiveEntryKind.SEDML, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/sed-ml.level-1.version-3").kind());
        assertEquals(ArchiveEntryKind.SEDML, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/sed-ml").kind());
        assertEquals(ArchiveEntryKind.OMEX, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/omex").kind());
        assertEquals(ArchiveEntryKind.METADATA, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/omex-metadata").kind());
        assertEquals(ArchiveEntryKind.METADATA, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/rdf-metadata").kind());
        // BioModels ships a colon where the separator should be a slash (measured 2026-11-19, CORP-4)
        assertEquals(ArchiveEntryKind.SBML, CombineArchiveFormat
                .parse("https://identifiers.org/combine.specifications:sbml").kind());
        // a query string is not part of the specification token
        assertEquals(ArchiveEntryKind.SBML, CombineArchiveFormat
                .parse("http://identifiers.org/combine.specifications/sbml?download=1").kind());
        assertEquals(ArchiveEntryKind.OTHER, CombineArchiveFormat.parse("").kind());
    }
}
