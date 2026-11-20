package org.masymos.core.extract;

import java.util.List;

/**
 * The schema versions this project claims to support, in one home.
 *
 * <p>The list is a <em>claim</em>, not a measurement: the three {@code *VersionMatrixTest} classes
 * prove it against fixtures and assert that their own fixture lists equal it, so neither can drift
 * without a visible test edit. The corpus lane ({@code CORP-4}) measures real samples against the
 * same list, which is why it lives in main rather than in a test.
 *
 * <p>{@code level} is {@code "-"} for a format that has no level (CellML). The same token is used in
 * {@code corpus/manifest.tsv}, so a harvested CellML file is not mistaken for a malformed row.
 */
public final class SupportedSchemaVersions {

    /** The SBML format name, as the corpus manifest and the lane spell it. */
    public static final String SBML = "SBML";

    /** The CellML format name, as the corpus manifest and the lane spell it. */
    public static final String CELLML = "CELLML";

    /** The SED-ML format name, as the corpus manifest and the lane spell it. */
    public static final String SEDML = "SEDML";

    private static final List<SchemaVersion> ALL = List.of(
            new SchemaVersion(SBML, "1", "1"),
            new SchemaVersion(SBML, "1", "2"),
            new SchemaVersion(SBML, "2", "2"),
            new SchemaVersion(SBML, "2", "4"),
            new SchemaVersion(SBML, "3", "1"),
            new SchemaVersion(SBML, "3", "2"),
            new SchemaVersion(CELLML, "-", "1.0"),
            new SchemaVersion(CELLML, "-", "1.1"),
            new SchemaVersion(CELLML, "-", "2.0"),
            new SchemaVersion(SEDML, "1", "1"),
            new SchemaVersion(SEDML, "1", "2"),
            new SchemaVersion(SEDML, "1", "3"),
            new SchemaVersion(SEDML, "1", "4"),
            new SchemaVersion(SEDML, "1", "5"));

    private SupportedSchemaVersions() {}

    /** Every claimed version, grouped by format in declaration order. */
    public static List<SchemaVersion> all() {
        return ALL;
    }

    /** The claimed versions of one format, in declaration order; empty for an unknown format. */
    public static List<SchemaVersion> forFormat(String format) {
        return ALL.stream().filter(version -> version.format().equals(format)).toList();
    }

    /** One claimed schema version: the format, its level ({@code "-"} where it has none) and version. */
    public record SchemaVersion(String format, String level, String version) {}
}
