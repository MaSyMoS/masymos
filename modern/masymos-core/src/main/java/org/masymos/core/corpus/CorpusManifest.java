package org.masymos.core.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The corpus manifest and its skip list, read and validated in one place (CORP-2).
 *
 * <p>The harvest script writes these two tab-separated files; the routine gate validates them
 * ({@link #load}) and the corpus lane replays them. Keeping the reader in {@code masymos-core} main
 * code is deliberate: a test class may not be imported by the lane, and two readers would drift.
 *
 * <p>The contract, fixed by {@link #header()} and {@link #skipHeader()}:
 * {@code source curation format level version id role file bytes sha256 url harvested} and
 * {@code sha256 stratum reason first-seen}. {@code file} is always relative to {@code corpus/files/}
 * and may never leave it; {@code level} is {@code "-"} for a format that has no level; a {@code
 * curated} row may never appear in the skip list, because the curated route is the gate.
 */
public record CorpusManifest(List<Row> rows, List<Skip> skips) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int ROW_FIELDS = 12;
    private static final int SKIP_FIELDS = 4;

    /** The curation route a row was drawn from; the route decides whether it gates or baselines. */
    public enum Curation {
        CURATED("curated"),
        NON_CURATED("non-curated");

        private final String token;

        Curation(String token) {
            this.token = token;
        }

        /** The spelling used in {@code manifest.tsv}. */
        public String token() {
            return token;
        }

        static Curation of(String token, String where) {
            for (var value : values()) {
                if (value.token.equals(token)) {
                    return value;
                }
            }
            throw new IllegalArgumentException(where + ": unknown curation '" + token + "'");
        }
    }

    /** What a row is inside its bundle: a whole document, an archive member, or a sidecar file. */
    public enum Role {
        MAIN("main"),
        MEMBER("member"),
        SIDECAR("sidecar");

        private final String token;

        Role(String token) {
            this.token = token;
        }

        /** The spelling used in {@code manifest.tsv}. */
        public String token() {
            return token;
        }

        static Role of(String token, String where) {
            for (var value : values()) {
                if (value.token.equals(token)) {
                    return value;
                }
            }
            throw new IllegalArgumentException(where + ": unknown role '" + token + "'");
        }
    }

    /** One harvested file. {@code file} is relative to {@code corpus/files/}. */
    public record Row(
            String source,
            Curation curation,
            String format,
            String level,
            String version,
            String id,
            Role role,
            String file,
            long bytes,
            String sha256,
            String url,
            String harvested) {}

    /** One known-bad file, legal only for a {@code non-curated} row. */
    public record Skip(String sha256, String stratum, String reason, String firstSeen) {}

    /** The exact manifest header, and the contract the harvest script must satisfy. */
    public static String header() {
        return String.join("\t",
                "source", "curation", "format", "level", "version", "id", "role", "file", "bytes", "sha256",
                "url", "harvested");
    }

    /** The exact skip-list header. */
    public static String skipHeader() {
        return String.join("\t", "sha256", "stratum", "reason", "first-seen");
    }

    /**
     * Reads both files and throws {@link IllegalArgumentException} naming the file and line for every
     * broken rule. A missing skip list means "nothing is skipped" and is not an error.
     */
    public static CorpusManifest load(Path manifest, Path skip) throws IOException {
        var rows = readRows(manifest);
        return new CorpusManifest(rows, readSkips(skip, rows));
    }

    /** The row with this hash; throws when the manifest has none. */
    public Row bySha256(String sha256) {
        return rows.stream()
                .filter(row -> row.sha256().equals(sha256))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no manifest row with sha256 " + sha256));
    }

    private static List<Row> readRows(Path manifest) throws IOException {
        var lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        var name = manifest.getFileName().toString();
        if (lines.isEmpty() || !lines.getFirst().equals(header())) {
            throw new IllegalArgumentException(name + ": header must be exactly '" + header() + "'");
        }
        var rows = new ArrayList<Row>();
        var files = new HashSet<String>();
        for (var index = 1; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            var where = name + ":" + (index + 1);
            var fields = line.split("\t", -1);
            if (fields.length != ROW_FIELDS) {
                throw new IllegalArgumentException(where + ": expected " + ROW_FIELDS + " columns, found " + fields.length);
            }
            var row = new Row(
                    fields[0].trim(),
                    Curation.of(fields[1].trim(), where),
                    fields[2].trim(),
                    fields[3].trim(),
                    fields[4].trim(),
                    fields[5].trim(),
                    Role.of(fields[6].trim(), where),
                    fields[7].trim(),
                    bytes(fields[8].trim(), where),
                    sha256(fields[9].trim(), where),
                    fields[10].trim(),
                    fields[11].trim());
            if (row.level().isEmpty()) {
                throw new IllegalArgumentException(where + ": level must be a value or '-', never empty");
            }
            if (row.version().isEmpty()) {
                throw new IllegalArgumentException(where + ": version must not be empty");
            }
            if (!files.add(row.file())) {
                throw new IllegalArgumentException(where + ": duplicate file '" + row.file() + "' in the manifest");
            }
            insideCache(row.file(), where);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<Skip> readSkips(Path skip, List<Row> rows) throws IOException {
        if (skip == null || Files.notExists(skip)) {
            return List.of();
        }
        var lines = Files.readAllLines(skip, StandardCharsets.UTF_8);
        var name = skip.getFileName().toString();
        if (lines.isEmpty() || !lines.getFirst().equals(skipHeader())) {
            throw new IllegalArgumentException(name + ": header must be exactly '" + skipHeader() + "'");
        }
        var skips = new ArrayList<Skip>();
        for (var index = 1; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            var where = name + ":" + (index + 1);
            var fields = line.split("\t", -1);
            if (fields.length != SKIP_FIELDS) {
                throw new IllegalArgumentException(where + ": expected " + SKIP_FIELDS + " columns, found " + fields.length);
            }
            var sha = sha256(fields[0].trim(), where);
            var row = rows.stream()
                    .filter(candidate -> candidate.sha256().equals(sha))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            where + ": skip names a sha256 with no manifest row - stale or mistyped entry"));
            if (row.curation() == Curation.CURATED) {
                throw new IllegalArgumentException(where
                        + ": a curated row can never be skipped; the curated route is the gate ('" + row.id() + "')");
            }
            skips.add(new Skip(sha, fields[1].trim(), fields[2].trim(), fields[3].trim()));
        }
        return List.copyOf(skips);
    }

    private static long bytes(String value, String where) {
        try {
            var parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException(value);
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(where + ": bytes must be a non-negative integer, found '" + value + "'");
        }
    }

    private static String sha256(String value, String where) {
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(where + ": sha256 must be 64 lowercase hex characters, found '" + value + "'");
        }
        return value;
    }

    private static void insideCache(String file, String where) {
        var path = Path.of(file);
        // A root component catches "/etc/passwd" on Windows too, where isAbsolute() is false for it.
        if (path.isAbsolute() || path.getRoot() != null || path.normalize().startsWith("..")) {
            throw new IllegalArgumentException(
                    where + ": file escapes corpus/files/ ('" + file + "'); the column is always relative to it");
        }
    }
}
