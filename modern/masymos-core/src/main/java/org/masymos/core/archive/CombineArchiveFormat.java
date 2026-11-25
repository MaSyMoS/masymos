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
    private static final Pattern DIGITS = Pattern.compile("\\d+");

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
        return switch (specificationToken(normalized)) {
            case "sbml" -> ArchiveEntryKind.SBML;
            case "cellml" -> ArchiveEntryKind.CELLML;
            case "sed-ml", "sedml" -> ArchiveEntryKind.SEDML;
            case "omex" -> ArchiveEntryKind.OMEX;
            case "omex-metadata", "rdf-metadata" -> ArchiveEntryKind.METADATA;
            default -> ArchiveEntryKind.OTHER;
        };
    }

    /**
     * The specification token a format URI names: the last path segment, with a query string, a
     * fragment, a trailing slash and a {@code combine.specifications:} colon spelling removed, and
     * every {@code level-<n>}/{@code version-<n>}/bare-number segment after it recognised as version
     * notation.
     *
     * <p>An exact token, not a substring: {@code sbml-notes} and {@code sbml.docs} are documentation,
     * and reading them as SBML turned a manifest typo into a silently unreadable entry.
     *
     * @return the token, or the empty string when the segment is not a plain specification name
     */
    static String specificationToken(String normalized) {
        var text = normalized;
        var end = text.length();
        var query = text.indexOf('?');
        if (query >= 0) {
            end = query;
        }
        var fragment = text.indexOf('#');
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        text = text.substring(0, end);
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        var segment = text.substring(text.lastIndexOf('/') + 1);
        var colon = segment.lastIndexOf(':');
        if (colon >= 0) {
            segment = segment.substring(colon + 1);
        }
        var parts = segment.split("\\.");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "";
        }
        for (var index = 1; index < parts.length; index++) {
            if (!isVersionNotation(parts[index])) {
                return "";
            }
        }
        return parts[0];
    }

    private static boolean isVersionNotation(String part) {
        return LEVEL.matcher(part).matches() || VERSION.matcher(part).matches() || DIGITS.matcher(part).matches();
    }

    private static Integer number(Matcher matcher) {
        if (!matcher.find()) {
            return null;
        }
        return Integer.valueOf(matcher.group(1));
    }
}
