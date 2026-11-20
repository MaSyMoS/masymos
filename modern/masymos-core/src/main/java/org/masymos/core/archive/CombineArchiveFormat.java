package org.masymos.core.archive;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The decoded format of a COMBINE archive content entry.
 *
 * <p>The raw format URI carries the specification, for example
 * {@code http://identifiers.org/combine.specifications/sbml.level-2.version-4}. The specification
 * is mapped to an {@link ArchiveEntryKind}, and the {@code level-<n>} and {@code version-<n>}
 * segments, when present, are extracted as numbers.</p>
 */
public record CombineArchiveFormat(ArchiveEntryKind kind, Integer level, Integer version, String formatUri) {
    private static final Pattern LEVEL = Pattern.compile("level-(\\d+)");
    private static final Pattern VERSION = Pattern.compile("version-(\\d+)");

    public CombineArchiveFormat {
        if (formatUri == null) {
            formatUri = "";
        }
    }

    /** The kind of content an archive entry holds. */
    public enum ArchiveEntryKind {
        SBML,
        CELLML,
        SEDML,
        OMEX,
        METADATA,
        OTHER
    }

    /**
     * Decodes a COMBINE format URI. Unknown or empty URIs decode to {@link ArchiveEntryKind#OTHER}
     * without failing, and absent level/version segments yield {@code null}.
     */
    public static CombineArchiveFormat parse(String formatUri) {
        var raw = formatUri == null ? "" : formatUri;
        var normalized = raw.trim().toLowerCase(Locale.ROOT);
        var kind = kind(normalized);
        return new CombineArchiveFormat(
                kind,
                number(LEVEL.matcher(normalized)),
                number(VERSION.matcher(normalized)),
                raw);
    }

    private static ArchiveEntryKind kind(String normalized) {
        if (normalized.contains("omex-metadata") || normalized.contains("rdf-metadata")) {
            return ArchiveEntryKind.METADATA;
        }
        if (normalized.contains("sbml")) {
            return ArchiveEntryKind.SBML;
        }
        if (normalized.contains("cellml")) {
            return ArchiveEntryKind.CELLML;
        }
        if (normalized.contains("sed-ml") || normalized.contains("sedml")) {
            return ArchiveEntryKind.SEDML;
        }
        if (normalized.endsWith("/omex")) {
            return ArchiveEntryKind.OMEX;
        }
        return ArchiveEntryKind.OTHER;
    }

    private static Integer number(Matcher matcher) {
        if (!matcher.find()) {
            return null;
        }
        return Integer.valueOf(matcher.group(1));
    }
}
