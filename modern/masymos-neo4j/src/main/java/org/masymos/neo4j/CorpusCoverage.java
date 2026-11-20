package org.masymos.neo4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.masymos.core.extract.SupportedSchemaVersions;

/**
 * Turns one corpus run's counts into the coverage matrix and its gaps (CORP-4).
 *
 * <p>The vocabulary is not typed here: entity types come from the writer's label whitelist and
 * relationships from its edge whitelist, both of which the write path already enforces, and the
 * versions come from {@link SupportedSchemaVersions}. A construct that never appears is a gap unless
 * {@code not-yet-seen} names it with a reason - that list is the one hand-kept part, and it is
 * asserted rather than trusted, so it cannot quietly grow to absorb every gap.
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
     */
    public static List<String> gaps(
            Map<String, Long> labels,
            Map<String, Long> relationships,
            Map<String, Long> versions,
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
            if (count(versions, key) == 0 && !notYetSeen.contains(VERSION + " " + key)) {
                gaps.add(VERSION + " " + key + " has no sample");
            }
        }
        return gaps;
    }

    /** The markdown report: one table per dimension, plus the explained gaps. */
    public static String report(
            Map<String, Long> labels,
            Map<String, Long> relationships,
            Map<String, Long> versions,
            Map<String, Long> strata,
            Set<String> notYetSeen) {
        var out = new StringBuilder();
        out.append("## Sample\n\n| stratum | files |\n| --- | --- |\n");
        new TreeMap<>(strata).forEach((key, value) -> out.append("| ").append(key).append(" | ").append(value).append(" |\n"));

        out.append("\n## Entity types\n\n| type | samples | covered |\n| --- | --- | --- |\n");
        for (var label : new TreeSet<>(ParsedModelGraphCypher.SUPPORTED_LABELS)) {
            out.append(row(label, count(labels, label), notYetSeen.contains(LABEL + " " + label)));
        }

        out.append("\n## Relationships\n\n| type | samples | covered |\n| --- | --- | --- |\n");
        for (var type : new TreeSet<>(ParsedModelGraphCypher.SUPPORTED_RELATIONSHIPS)) {
            out.append(row(type, count(relationships, type), notYetSeen.contains(RELATIONSHIP + " " + type)));
        }

        out.append("\n## Schema versions\n\n| version | samples | covered |\n| --- | --- | --- |\n");
        for (var version : SupportedSchemaVersions.all()) {
            var key = versionKey(version);
            out.append(row(key, count(versions, key), notYetSeen.contains(VERSION + " " + key)));
        }

        var gaps = gaps(labels, relationships, versions, notYetSeen);
        out.append("\n## Gaps\n\n");
        if (gaps.isEmpty()) {
            out.append("None: every construct the writer accepts was produced by at least one sample.\n");
        } else {
            gaps.forEach(gap -> out.append("- ").append(gap).append("\n"));
        }
        return out.toString();
    }

    /** The version key used by the counts and by a {@code not-yet-seen} row: {@code FORMAT level version}. */
    public static String versionKey(SupportedSchemaVersions.SchemaVersion version) {
        return version.format() + " " + version.level() + " " + version.version();
    }

    private static String row(String key, long count, boolean explained) {
        var covered = count > 0 ? "yes" : (explained ? "explained" : "**no**");
        return "| " + key + " | " + count + " | " + covered + " |\n";
    }

    private static long count(Map<String, Long> counts, String key) {
        return counts.getOrDefault(key, 0L);
    }
}
