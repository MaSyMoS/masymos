package org.masymos.neo4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.SourceDocument;
import org.masymos.core.extract.SupportedSchemaVersions;

/**
 * Turns one corpus run's counts into the coverage matrix and its gaps (CORP-4).
 *
 * <p>The vocabulary is not typed here: entity types come from the writer's label whitelist and
 * relationships from its edge whitelist, both of which the write path already enforces. The version
 * dimension is measured, not claimed: each parsed document states its own schema version ({@link
 * #statedVersions}), so an archive contributes one entry per member rather than the archive's own
 * label (CORP-12), and a version the sample produced but {@link SupportedSchemaVersions} does not list
 * is named with the documents behind it instead of vanishing (CORP-11). A construct that never
 * appears is a gap unless {@code not-yet-seen} names it with a reason - that list is the one hand-kept
 * part, and it is asserted rather than trusted, so it cannot quietly grow to absorb every gap.
 *
 * <p>The dimensions are the sample count, the reduced reads ({@code CORP-9}), the externally enriched
 * documents ({@link #report}'s {@code externalCmeta}, {@code CORP-10}/{@code CML-3}), the stored copies
 * ({@code CORP-16}), the entity types, the relationships, the schema versions and the gaps.
 */
public final class CorpusCoverage {

    /** Key prefix used by the {@code not-yet-seen} rows for an entity type. */
    public static final String LABEL = "LABEL";

    /** Key prefix used by the {@code not-yet-seen} rows for a relationship type. */
    public static final String RELATIONSHIP = "RELATIONSHIP";

    /** Key prefix used by the {@code not-yet-seen} rows for a schema version. */
    public static final String VERSION = "VERSION";

    private CorpusCoverage() {}

    /**
     * The constructs the sample did not produce and no {@code not-yet-seen} row explains, one line
     * each; empty when the sample covers the vocabulary.
     *
     * <p>{@code versions} maps a version key to the documents that stated it, so a claimed version is
     * covered when at least one document states it.
     */
    public static List<String> gaps(
            Map<String, Long> labels,
            Map<String, Long> relationships,
            Map<String, Set<String>> versions,
            Set<String> notYetSeen) {
        var gaps = new ArrayList<String>();
        for (var label : new TreeSet<>(ParsedModelGraphCypher.SUPPORTED_LABELS)) {
            if (count(labels, label) == 0 && !notYetSeen.contains(LABEL + " " + label)) {
                gaps.add(LABEL + " " + label + " was never produced by any sample");
            }
        }
        for (var type : new TreeSet<>(ParsedModelGraphCypher.SUPPORTED_RELATIONSHIPS)) {
            if (count(relationships, type) == 0 && !notYetSeen.contains(RELATIONSHIP + " " + type)) {
                gaps.add(RELATIONSHIP + " " + type + " was never produced by any sample");
            }
        }
        for (var version : SupportedSchemaVersions.all()) {
            var key = versionKey(version);
            if (documentCount(versions, key) == 0 && !notYetSeen.contains(VERSION + " " + key)) {
                gaps.add(VERSION + " " + key + " has no sample");
            }
        }
        return gaps;
    }

    /**
     * The versions the parsed documents state of themselves, keyed by the document that stated them,
     * plus the documents that state none.
     *
     * <p>This is what the version dimension counts (CORP-12): the manifest row's {@code format level
     * version} is a claim about a file, and an archive row is {@code OMEX - -}, so counting rows could
     * never attribute a member's SED-ML version. The document's own {@code sbml.level}/{@code
     * sbml.version}, {@code cellml.version} or {@code sedml.level}/{@code sedml.version} properties are
     * already parsed, so the count is taken here rather than from the manifest.
     */
    public static StatedVersions statedVersions(List<ParsedModel> documents) {
        var byVersion = new TreeMap<String, Set<String>>();
        var unversioned = new TreeSet<String>();
        for (var document : documents) {
            var key = statedVersionKey(document);
            if (key == null) {
                unversioned.add(document.reference().fileId());
            } else {
                byVersion.computeIfAbsent(key, ignored -> new TreeSet<>()).add(document.reference().fileId());
            }
        }
        return new StatedVersions(byVersion, unversioned);
    }

    /**
     * What one parsed document says about its own schema, in the report's key spelling ({@code FORMAT
     * level version}), or {@code null} when it states no version at all.
     */
    public static String statedVersionKey(ParsedModel document) {
        var properties = document.properties();
        var sbmlLevel = value(properties.get("sbml.level"));
        var sbmlVersion = value(properties.get("sbml.version"));
        if (sbmlLevel != null && sbmlVersion != null) {
            return SupportedSchemaVersions.SBML + " " + sbmlLevel + " " + sbmlVersion;
        }
        var cellmlVersion = value(properties.get("cellml.version"));
        if (cellmlVersion != null) {
            return SupportedSchemaVersions.CELLML + " - " + cellmlVersion;
        }
        var sedmlLevel = value(properties.get("sedml.level"));
        var sedmlVersion = value(properties.get("sedml.version"));
        if (sedmlLevel != null && sedmlVersion != null) {
            return SupportedSchemaVersions.SEDML + " " + sedmlLevel + " " + sedmlVersion;
        }
        return null;
    }

    /** How many documents stated a version key; {@code 0} when none did. */
    public static long documentCount(Map<String, Set<String>> versions, String key) {
        var documents = versions.get(key);
        return documents == null ? 0L : documents.size();
    }

    /**
     * The stated versions that are not in the claim, each with the documents that produced it
     * (CORP-11). The claim is what {@link SupportedSchemaVersions} lists; a version outside it is
     * reported rather than dropped, and rather than added to the claim on the strength of a corpus
     * file that no test reads.
     */
    public static Map<String, Set<String>> outsideTheClaim(Map<String, Set<String>> versions) {
        var claimed = new LinkedHashSet<String>();
        SupportedSchemaVersions.all().forEach(version -> claimed.add(versionKey(version)));
        var outside = new TreeMap<String, Set<String>>();
        versions.forEach((key, documents) -> {
            if (!claimed.contains(key)) {
                outside.put(key, new TreeSet<>(documents));
            }
        });
        return outside;
    }

    /**
     * The markdown report: one table per dimension, the versions the sample states outside the claim,
     * and the explained gaps.
     *
     * <p>{@code reducedReads} is the one measurement that is a caveat rather than a coverage claim:
     * the samples JSBML could only read after elements without a reading parser were dropped
     * ({@code CORP-9}). Such a document still ingests, so without this section the matrix would count
     * it as fully read and a reader could not tell that annotations in a manufacturer namespace were
     * never indexed.
     *
     * <p>{@code externalCmeta} is measured for the same reason ({@code CORP-10}): a harvested
     * {@code @@cmeta} sidecar that no document read contributes nothing and is indistinguishable from a
     * model that never had one, so the section names the documents that were enriched.
     *
     * <p>{@code storedDocuments} is the same kind of caveat ({@code CORP-16}): the manifest already holds
     * documents above the storage ceiling, so the lane keeps a shortened copy of them and a report that
     * does not say so is silent about its own fidelity.
     */
    public static String report(
            Map<String, Long> labels,
            Map<String, Long> relationships,
            Map<String, Set<String>> versions,
            Set<String> unversioned,
            Map<String, Long> strata,
            Map<String, String> reducedReads,
            Set<String> externalCmeta,
            Set<String> notYetSeen,
            List<StoredCopy> storedDocuments) {
        var out = new StringBuilder();
        out.append("## Sample\n\n| stratum | files |\n| --- | --- |\n");
        new TreeMap<>(strata).forEach((key, value) -> out.append("| ").append(key).append(" | ").append(value).append(" |\n"));

        out.append("\n## Reduced reads\n\n");
        if (reducedReads.isEmpty()) {
            out.append("None: every document was read in one pass.\n");
        } else {
            out.append(reducedReads.size())
                    .append(" sample(s) needed the reduced read, so content JSBML cannot parse was")
                    .append(" dropped before it (CORP-9); the rest were read in one pass.\n")
                    .append("\n| sample | what the read lost |\n| --- | --- |\n");
            new TreeMap<>(reducedReads).forEach((sample, dropped) ->
                    out.append("| ").append(sample).append(" | ").append(dropped).append(" |\n"));
        }

        out.append("\n## External cmeta\n\n");
        if (externalCmeta.isEmpty()) {
            out.append("None: no sampled CellML document was enriched from a harvested @@cmeta sidecar.\n");
        } else {
            out.append(externalCmeta.size())
                    .append(" document(s) were enriched from a harvested `@@cmeta` sidecar, read by the")
                    .append(" shipped `RemotePmrCellmlExternalCmetaResolver` from the cached sibling of the")
                    .append(" model; external cmeta stays opt-in (decision 17, `CML-3`).\n")
                    .append("\n| document |\n| --- |\n");
            new TreeSet<>(externalCmeta).forEach(document -> out.append("| ").append(document).append(" |\n"));
        }

        appendStoredDocuments(out, storedDocuments);

        out.append("\n## Entity types\n\n| type | samples | covered |\n| --- | --- | --- |\n");
        for (var label : new TreeSet<>(ParsedModelGraphCypher.SUPPORTED_LABELS)) {
            out.append(row(label, count(labels, label), notYetSeen.contains(LABEL + " " + label)));
        }

        out.append("\n## Relationships\n\n| type | samples | covered |\n| --- | --- | --- |\n");
        for (var type : new TreeSet<>(ParsedModelGraphCypher.SUPPORTED_RELATIONSHIPS)) {
            out.append(row(type, count(relationships, type), notYetSeen.contains(RELATIONSHIP + " " + type)));
        }

        out.append("\n## Schema versions\n\n| version | documents | covered |\n| --- | --- | --- |\n");
        for (var version : SupportedSchemaVersions.all()) {
            var key = versionKey(version);
            out.append(row(key, documentCount(versions, key), notYetSeen.contains(VERSION + " " + key)));
        }

        appendOutsideTheClaim(out, versions, unversioned);

        var gaps = gaps(labels, relationships, versions, notYetSeen);
        out.append("\n## Gaps\n\n");
        if (gaps.isEmpty()) {
            out.append("None: every construct the writer accepts was produced by at least one sample.\n");
        } else {
            gaps.forEach(gap -> out.append("- ").append(gap).append("\n"));
        }
        return out.toString();
    }

    /**
     * What the storage path keeps of each document the sample produced ({@code CORP-16}). The manifest
     * already holds documents above {@link SourceDocument#DEFAULT_MAX_BYTES}, so the write path cuts
     * them - and until this section existed no line of the report said so, which made the one artifact
     * the lane produces silent about its own fidelity.
     *
     * <p>{@code truncated} is the flag {@link SourceDocument#from} sets; {@code documents over the
     * ceiling} is a comparison with the default ceiling. The two differ when a run stores under a raised
     * one ({@code masymos.storage.document-max-bytes}): such a document is over the default and still
     * whole, and only the flag says what the store holds, so the section reports both.
     */
    private static void appendStoredDocuments(StringBuilder out, List<StoredCopy> storedDocuments) {
        out.append("\n## Stored documents\n\n");
        if (storedDocuments.isEmpty()) {
            out.append("None: no document was measured against the storage ceiling.\n");
            return;
        }
        var truncated = 0L;
        var overTheCeiling = 0L;
        var largest = storedDocuments.get(0);
        for (var document : storedDocuments) {
            truncated += document.truncated() ? 1 : 0;
            overTheCeiling += document.originalBytes() > SourceDocument.DEFAULT_MAX_BYTES ? 1 : 0;
            if (document.originalBytes() > largest.originalBytes()) {
                largest = document;
            }
        }
        var ceilingMiB = SourceDocument.DEFAULT_MAX_BYTES / (1024 * 1024);
        out.append(storedDocuments.size()).append(" document(s) were measured against the storage")
                .append(" ceiling: ").append(truncated).append(" carry the truncation flag, and ")
                .append(overTheCeiling).append(" of the ").append(storedDocuments.size())
                .append(" exceed the default ").append(ceilingMiB).append(" MiB ceiling")
                .append(" (`SourceDocument.DEFAULT_MAX_BYTES`). A cut copy is shorter than the original,")
                .append(" so `documentBytes` still reports the original size while `documentTruncated` is")
                .append(" true (STO-2). The largest is `").append(largest.fileId()).append("` at ")
                .append(largest.originalBytes()).append(" bytes.\n")
                .append("\n| measure | value |\n| --- | --- |\n")
                .append("| documents | ").append(storedDocuments.size()).append(" |\n")
                .append("| truncated | ").append(truncated).append(" |\n")
                .append("| largest bytes | ").append(largest.originalBytes()).append(" |\n")
                .append("| documents over the ceiling | ").append(overTheCeiling).append(" |\n");
    }

    /**
     * The section that keeps the version dimension from going silent (CORP-11): every version a
     * document stated that the claim does not list, and every document that states no version at all.
     * Both used to disappear between the {@code ## Schema versions} table and the {@code ## Gaps} list,
     * which iterate the claim.
     */
    private static void appendOutsideTheClaim(
            StringBuilder out, Map<String, Set<String>> versions, Set<String> unversioned) {
        out.append("\n## Versions outside the claim\n\n");
        var outside = outsideTheClaim(versions);
        if (outside.isEmpty() && unversioned.isEmpty()) {
            out.append("None: every document states a version this project claims.\n");
            return;
        }
        if (!outside.isEmpty()) {
            out.append(outside.size())
                    .append(" version(s) were stated by a document and are not in the claim")
                    .append(" (`SupportedSchemaVersions`), so the table above - which measures the claim,")
                    .append(" not the sample - has no row for them; each is named with the documents that")
                    .append(" produced it (CORP-11). The claim is unchanged on purpose: it is the set")
                    .append(" `SbmlVersionMatrixTest` proves with committed fixtures, and a version seen only")
                    .append(" in a gitignored corpus file is not a claim until a fixture is committed for")
                    .append(" it (`model-version-matrix.md`).\n")
                    .append("\n| version | documents | samples |\n| --- | --- | --- |\n");
            outside.forEach((version, documents) -> out.append("| ").append(version).append(" | ")
                    .append(documents.size()).append(" | ")
                    .append(String.join(", ", new TreeSet<>(documents))).append(" |\n"));
        }
        if (!unversioned.isEmpty()) {
            out.append("\n").append(unversioned.size())
                    .append(" document(s) state no schema version of their own, so no version row counts")
                    .append(" them: ").append(String.join(", ", new TreeSet<>(unversioned))).append("\n");
        }
    }

    /** The version key used by the counts and by a {@code not-yet-seen} row: {@code FORMAT level version}. */
    public static String versionKey(SupportedSchemaVersions.SchemaVersion version) {
        return version.format() + " " + version.level() + " " + version.version();
    }

    /**
     * What the storage path keeps of one parsed document: the size before truncation, and whether the
     * copy is cut. Both come from {@link SourceDocument#from}, the call the write path makes, so the
     * lane measures that rule instead of re-deriving it - it does not write the copy
     * ({@code CorpusDocuments.ReadDocument} records why the lane cannot).
     *
     * <p>Deliberately not {@code org.masymos.core.model.StoredDocument}: that record is a document read
     * back out of the graph, content included, and nothing on this path has been read back.
     */
    public record StoredCopy(String fileId, long originalBytes, boolean truncated) {
        public StoredCopy {
            if (fileId == null || fileId.isBlank()) {
                throw new IllegalArgumentException("a stored copy needs the file id it was stored under");
            }
            if (originalBytes < 0) {
                throw new IllegalArgumentException("originalBytes must not be negative");
            }
        }
    }

    /** The versions a run's documents stated, and the documents that stated none (see {@link #statedVersions}). */
    public record StatedVersions(Map<String, Set<String>> byVersion, Set<String> unversioned) {
        public StatedVersions {
            byVersion = Map.copyOf(byVersion);
            unversioned = Set.copyOf(unversioned);
        }
    }

    private static String row(String key, long count, boolean explained) {
        var covered = count > 0 ? "yes" : (explained ? "explained" : "**no**");
        return "| " + key + " | " + count + " | " + covered + " |\n";
    }

    private static long count(Map<String, Long> counts, String key) {
        return counts.getOrDefault(key, 0L);
    }

    /** A property value, or {@code null} when it is absent or blank. */
    private static String value(String property) {
        return property == null || property.isBlank() ? null : property.trim();
    }
}
