package org.masymos.core.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The citation gate (DOC-9): a {@code File.java:line} in a parity document names a line that exists and
 * carries code, and the documents are re-read when a file they cite gains or loses lines.
 *
 * <p>Two failures motivated it, both measured on 2026-11-20. The first is an anchor that exists but
 * names nothing: 233 block ranges ended on a closing brace and 15 point citations landed on a
 * {@code }} or a blank line, so a reader following them saw no code at all. The second is drift, and it
 * is the expensive one: {@code CML-1} moved 219 citations, {@code CORP-9} moved 91, {@code CORP7-9}
 * left every {@code SedmlModelParser} citation 32 lines short, {@code CORP7-13} shifted
 * {@code Neo4jResourceQueryRepository} by seven, and decision 44 moved two rows by eight. A wrong line
 * number is still a <em>valid</em> line number, so no compiler, formatter or encoding gate can see it -
 * only a re-read can.</p>
 *
 * <p>This gate does the two mechanical halves of that re-read. It cannot judge whether a line carries
 * the construct the prose claims; that stays a human step, and the canary is what makes the human step
 * happen at the right moment instead of a year later.</p>
 *
 * <p>The canary is {@code src/test/resources/citation-canary.tsv}: one row per cited file with the line
 * count it had when the documents were last verified. A count that no longer matches fails this test and
 * names every document row that cites the file. Re-verifying those rows is deliberate work; after it,
 * refresh the canary with {@code mvn -pl masymos-core test -Dtest=CitationAuditTest
 * -Dmasymos.citations.refresh=true}.</p>
 *
 * <p>Deliberately not checked: an anchor on a comment line, and an anchor on an {@code import}. The
 * prose sometimes names those constructs - {@code query-parity} cites {@code StructureQueryUtil.java:12}
 * <em>because</em> the class is "entirely commented out", and {@code sedml-parity} says "imports
 * {@code org.jlibsedml.*}" and cites the import block that proves it - so a gate that rejected either
 * would be red on a correct document. An anchor that exists but carries the wrong construct still needs
 * a reader.</p>
 *
 * <p>A bare {@code :117} is checked only when it falls inside the file named before it. A table cell may
 * name a second file without repeating its prefix ({@code cellml-parity} cites
 * {@code CellmlModelParser.java:201-215} and then {@code isTruthy, :1029-1030} beside a
 * {@code ParsedModelGraphMapper} reference), so an out-of-range bare figure is likelier to be a
 * misattribution by this parser than a defect in the document.</p>
 */
class CitationAuditTest {
    private static final List<String> DOCUMENTS = List.of(
            "sbml-parity.md",
            "cellml-parity.md",
            "sedml-parity.md",
            "owl-parity.md",
            "storage-parity.md",
            "query-parity.md",
            "surfaces-parity.md",
            "api-reference.md");

    private static final Path CANARY = Path.of("src", "test", "resources", "citation-canary.tsv");
    private static final String REFRESH = "masymos.citations.refresh";

    private static final Pattern FILE_NAME =
            Pattern.compile("([A-Za-z0-9_/.]+)\\.java");
    private static final Pattern FILE_TOKEN =
            Pattern.compile("([A-Za-z0-9_/.]+)\\.java:(\\d+)(?:-(\\d+)|`-`:(\\d+))?");
    private static final Pattern BARE_TOKEN =
            Pattern.compile("(?<![\\w.]):(\\d+)(?:-(\\d+)|`-`:(\\d+))?");
    /** DG-004: a Current row's State cell, with the word that gives the date its meaning. */
    private static final Pattern CURRENT_ROW_WITH_DATE =
            Pattern.compile("Current[^|]*\\b(?:verified|measured|superseded)\\b[^|]*\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern VERIFICATION_DATE =
            Pattern.compile("^Verification date:\\s*(\\d{4}-\\d{2}-\\d{2})");

    @Test
    void everyCitationNamesALineThatExistsAndCarriesCode() throws IOException {
        var root = rootOrSkip();
        var findings = audit(readDocuments(root), javaFileIndex(root));
        assertEquals(List.of(), findings, "every citation must name an existing line of code");
    }

    @Test
    void everyCitedFileHasTheLineCountTheCanaryRecorded() throws IOException {
        Assumptions.assumeFalse(Boolean.getBoolean(REFRESH),
                "refresh mode: the canary is being rewritten, not compared");
        var root = rootOrSkip();
        var recorded = readCanary();
        var actual = citedFiles(root);

        var findings = new ArrayList<String>();
        for (var entry : actual.entrySet()) {
            var before = recorded.get(entry.getKey());
            var cited = entry.getValue();
            if (before == null) {
                findings.add(entry.getKey() + " is cited by " + cited.rows().size()
                        + " row(s) but absent from the canary: refresh it");
            } else if (before != cited.lineCount()) {
                findings.add(entry.getKey() + " has " + cited.lineCount() + " lines, the canary recorded "
                        + before + "; re-read " + cited.rows().size() + " row(s) in "
                        + String.join(", ", cited.documents()));
            }
        }
        for (var file : recorded.keySet()) {
            if (!actual.containsKey(file)) {
                findings.add(file + " is in the canary but no longer cited: refresh it");
            }
        }

        assertEquals(List.of(), findings,
                "a cited file that gained or lost lines invalidates every citation into it; re-verify the"
                        + " rows named above, then refresh the canary with -D" + REFRESH + "=true");
    }

    /**
     * The gate must be able to fail. Without this, a broken parser would report no findings and every
     * other test here would be green over nothing - the failure mode this project has already paid for
     * once (a gate that looked like it ran).
     */
    @Test
    void theAuditReportsABraceAnchorAndALinePastTheEndOnSyntheticInput() throws Exception {
        var workspace = Files.createTempDirectory("citation-audit");
        var source = workspace.resolve("Synthetic.java");
        Files.writeString(source, String.join("\n",
                "class Synthetic {",
                "    void method() {",
                "        call();",
                "    }",
                "}",
                ""), StandardCharsets.UTF_8);

        var documents = Map.of("synthetic-parity.md", String.join("\n",
                "a point on code: `Synthetic.java:3`",
                "a range ending on the brace: `Synthetic.java:2-4`",
                "a point on a blank line: `Synthetic.java:6`",
                "a line past the end: `Synthetic.java:99`",
                ""));
        var index = Map.of("Synthetic.java", List.of(source));

        var findings = audit(documents, index);

        assertTrue(findings.stream().anyMatch(finding -> finding.contains("Synthetic.java:2-4")),
                "a range that closes on a brace must be reported: " + findings);
        assertTrue(findings.stream().anyMatch(finding -> finding.contains("Synthetic.java:6")),
                "a point on a blank line must be reported: " + findings);
        assertTrue(findings.stream().anyMatch(finding -> finding.contains("Synthetic.java:99")),
                "a line past the end must be reported: " + findings);
        assertFalse(findings.stream().anyMatch(finding -> finding.contains("Synthetic.java:3")),
                "a point on a code line must not be reported: " + findings);
    }

    /** Rewrites the canary. Only runs with {@code -Dmasymos.citations.refresh=true}. */
    @Test
    void refreshWritesTheCanaryWhenAsked() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean(REFRESH),
                "pass -D" + REFRESH + "=true to rewrite " + CANARY);
        var root = rootOrSkip();
        var counts = citedFiles(root);

        var out = new StringBuilder();
        out.append("# DOC-9 citation canary: cited file -> line count when the eight parity documents\n");
        out.append("# were last verified. A mismatch fails CitationAuditTest and names the rows to\n");
        out.append("# re-read; refresh afterwards with:\n");
        out.append("#   mvn -pl masymos-core test -Dtest=CitationAuditTest -D").append(REFRESH).append("=true\n");
        for (var entry : counts.entrySet()) {
            out.append(entry.getKey()).append('\t').append(entry.getValue().lineCount()).append('\n');
        }
        Files.writeString(CANARY, out.toString(), StandardCharsets.UTF_8);
        assertTrue(counts.size() > 50,
                "the canary must cover every cited file, not a handful: " + counts.size());
    }

    // ---- the audit itself ---------------------------------------------------------------------

    /**
     * One finding per defective citation, in document order. Package-private and free of static state so
     * the self-test can drive it over a temporary tree.
     */
    static List<String> audit(Map<String, String> documents, Map<String, List<Path>> index) {
        var lines = new LinkedHashMap<Path, List<String>>();
        var findings = new ArrayList<String>();

        for (var document : documents.entrySet()) {
            var number = 0;
            for (var text : document.getValue().split("\n", -1)) {
                number++;
                for (var citation : citationsIn(text)) {
                    if (citation.name() == null) {
                        continue;
                    }
                    var where = document.getKey() + ":" + number + " cites " + citation.token();
                    var file = resolve(citation.name(), index);
                    if (file == null) {
                        findings.add(where + ", but no such source file is in the checkout");
                        continue;
                    }
                    var source = lines.computeIfAbsent(file, CitationAuditTest::readOrEmpty);
                    if (citation.from() < 1 || citation.end() > source.size()
                            || citation.from() > citation.end()) {
                        if (citation.named()) {
                            findings.add(where + ", which is outside " + file.getFileName() + "'s "
                                    + source.size() + " lines");
                        }
                        continue;
                    }
                    checkEndpoint(where + " opens on", source.get(citation.from() - 1), findings);
                    if (citation.end() != citation.from()) {
                        checkEndpoint(where + " closes on", source.get(citation.end() - 1), findings);
                    }
                }
            }
        }
        return List.copyOf(findings);
    }

    /**
     * DG3-1: a simple name that both trees carry is resolved by preference, not by evidence.
     *
     * <p>{@code resolve} shortens such a name to "the only candidate in {@code modern/}, else the
     * only one in {@code code/}", so a citation meant for the legacy file is checked against the
     * modern one and the canary records that file's line count. Measured on 2026-11-23: the index
     * carries 487 simple names and exactly one with two candidates ({@code IndexText.java} -
     * {@code modern/.../query/} and {@code code/.../util/}), and none of the eight documents cites
     * it, so the gate is green today and blind the moment one does. A path-bearing shorthand of
     * three or more segments ({@code LQ/types/ModelQuery.java}) resolves by its suffix and is not a
     * guess.</p>
     */
    @Test
    void noCitedSimpleNameIsResolvedByTheTreePreference() throws IOException {
        var root = rootOrSkip();

        var findings = treePreferredCitations(readDocuments(root), javaFileIndex(root));

        assertEquals(List.of(), findings,
                "a cited name that both trees carry is resolved by preference, not by evidence; cite it"
                        + " with the path segments that pick the intended file");
    }

    @Test
    void theTreePreferenceGuardReportsATwoTreeNameOnSyntheticInput() throws Exception {
        var workspace = Files.createTempDirectory("citation-ambiguity");
        var modern = Files.createDirectories(workspace.resolve("modern").resolve("query"))
                .resolve("IndexText.java");
        var legacy = Files.createDirectories(workspace.resolve("code").resolve("util"))
                .resolve("IndexText.java");
        Files.writeString(modern, "class IndexText {}\n", StandardCharsets.UTF_8);
        Files.writeString(legacy, "class IndexText {}\n", StandardCharsets.UTF_8);
        var index = Map.of("IndexText.java", List.of(modern, legacy));

        var bare = treePreferredCitations(
                Map.of("synthetic-parity.md", "a point on `IndexText.java:1`\n"), index);
        var bySuffix = treePreferredCitations(
                Map.of("synthetic-parity.md", "a point on `src/query/IndexText.java:1`\n"), index);

        assertEquals(1, bare.size(), "a name both trees carry must be reported: " + bare);
        assertEquals(List.of(), bySuffix,
                "a shorthand whose tail picks one file is not a guess: " + bySuffix);
    }

    /**
     * Every citation whose resolution would be decided by the tree preference rather than by the name
     * itself ({@code DG3-1}). Package-private so the self-test can drive it over a temporary index.
     */
    static List<String> treePreferredCitations(Map<String, String> documents, Map<String, List<Path>> index) {
        var findings = new LinkedHashSet<String>();
        for (var document : documents.entrySet()) {
            var number = 0;
            for (var text : document.getValue().split("\n", -1)) {
                number++;
                for (var citation : citationsIn(text)) {
                    if (citation.name() == null) {
                        continue;
                    }
                    var reason = treePreferenceReason(citation.name(), index);
                    if (reason != null) {
                        findings.add(document.getKey() + ":" + number + " cites " + citation.token()
                                + ", " + reason);
                    }
                }
            }
        }
        return List.copyOf(findings);
    }

    /** What {@code resolve} would do with an ambiguous name, or {@code null} when it is not ambiguous. */
    private static String treePreferenceReason(String name, Map<String, List<Path>> index) {
        var simple = name.substring(name.lastIndexOf('/') + 1);
        var candidates = index.get(simple);
        if (candidates == null || candidates.size() <= 1) {
            return null;
        }
        var segments = name.split("/");
        if (segments.length > 2) {
            var suffix = "/" + String.join("/", Arrays.copyOfRange(segments, 1, segments.length));
            var bySuffix = candidates.stream()
                    .filter(path -> path.toString().replace('\\', '/').endsWith(suffix))
                    .toList();
            if (bySuffix.size() == 1) {
                return null;
            }
        }
        for (var tree : List.of("/modern/", "/code/")) {
            var inTree = candidates.stream()
                    .filter(path -> path.toString().replace('\\', '/').contains(tree))
                    .toList();
            if (inTree.size() == 1) {
                return "the name has " + candidates.size() + " candidates, so the tree preference decides"
                        + " it by taking the single one under " + tree + ": " + candidates;
            }
        }
        return "the name has " + candidates.size() + " candidates and none resolves: " + candidates;
    }

    private static void checkEndpoint(String where, String line, List<String> findings) {
        var text = line.trim();
        if (text.isEmpty()) {
            findings.add(where + " a blank line");
        } else if (text.matches("[})\\]]+[;,]?")) {
            findings.add(where + " a lone closing brace (" + text + ")");
        }
    }

    /**
     * Every citation on one document line, in reading order. A bare {@code :117} inherits the nearest
     * preceding {@code Name.java}, whether or not that name carried a line number of its own: requiring
     * the number is what once made {@code query-parity}'s "the class {@code StructureQueryUtil.java} is
     * entirely commented out ({@code :12}, {@code :22})" resolve against the file named before it.
     */
    static List<Citation> citationsIn(String line) {
        var events = new ArrayList<Citation>();
        var nameStarts = new ArrayList<Integer>();
        var nameEnds = new ArrayList<Integer>();
        var nameList = new ArrayList<String>();
        for (Matcher name = FILE_NAME.matcher(line); name.find(); ) {
            nameStarts.add(name.start());
            nameEnds.add(name.end());
            nameList.add(name.group(1) + ".java");
        }
        for (Matcher m = FILE_TOKEN.matcher(line); m.find(); ) {
            events.add(new Citation(m.group(1) + ".java", m.start(), number(m.group(2)),
                    number(m.group(3) == null ? m.group(4) : m.group(3)), m.group(), true));
        }
        for (Matcher m = BARE_TOKEN.matcher(line); m.find(); ) {
            if (insideAFileToken(events, m.start())) {
                continue;
            }
            events.add(new Citation(fileBefore(nameStarts, nameEnds, nameList, m.start()), m.start(),
                    number(m.group(1)), number(m.group(2) == null ? m.group(3) : m.group(2)),
                    m.group(), false));
        }
        events.sort(Comparator.comparingInt(Citation::at));
        return List.copyOf(events);
    }

    private static boolean insideAFileToken(List<Citation> fileTokens, int position) {
        for (var token : fileTokens) {
            if (token.at() < position && token.at() + token.token().length() > position) {
                return true;
            }
        }
        return false;
    }

    /** The file named last before {@code position}, by name alone: a bare figure needs no line number there. */
    private static String fileBefore(List<Integer> starts, List<Integer> ends, List<String> names, int position) {
        String found = null;
        for (var index = 0; index < names.size(); index++) {
            if (ends.get(index) > position) {
                break;
            }
            found = names.get(index);
        }
        return found;
    }

    // ---- resolution and the canary -------------------------------------------------------------

    private static Map<String, String> readDocuments(Path root) throws IOException {
        var documents = new LinkedHashMap<String, String>();
        for (var name : DOCUMENTS) {
            var path = root.resolve("docs").resolve("modernization").resolve(name);
            if (Files.isRegularFile(path)) {
                documents.put(name, Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return documents;
    }

    private static Map<String, List<Path>> javaFileIndex(Path root) throws IOException {
        var index = new LinkedHashMap<String, List<Path>>();
        for (var tree : List.of("modern", "code")) {
            var base = root.resolve(tree);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.toString().replace('\\', '/').contains("/target/"))
                        .forEach(path -> index
                                .computeIfAbsent(path.getFileName().toString(), key -> new ArrayList<>())
                                .add(path));
            }
        }
        return index;
    }

    /** The one file a name means, or {@code null} when the checkout has none or the name is ambiguous. */
    private static Path resolve(String name, Map<String, List<Path>> index) {
        var simple = name.substring(name.lastIndexOf('/') + 1);
        var candidates = index.get(simple);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        // "LQ/types/ModelQuery.java" and "MN/Neo4jModelRepository.java": the shorthand is the first
        // segment, everything after it is the path the document meant.
        var segments = name.split("/");
        if (segments.length > 2) {
            var suffix = "/" + String.join("/", Arrays.copyOfRange(segments, 1, segments.length));
            var bySuffix = candidates.stream()
                    .filter(path -> path.toString().replace('\\', '/').endsWith(suffix))
                    .toList();
            if (bySuffix.size() == 1) {
                return bySuffix.getFirst();
            }
        }
        for (var tree : List.of("/modern/", "/code/")) {
            var inTree = candidates.stream()
                    .filter(path -> path.toString().replace('\\', '/').contains(tree))
                    .toList();
            if (inTree.size() == 1) {
                return inTree.getFirst();
            }
        }
        return null;
    }

    /**
     * DG-004: the index has no single verification date, because the rows outgrew the one the header
     * used to promise. Every row of the Current table must carry its own - the word that says what
     * the date means (`verified`, `measured` or `superseded`) and the date itself - or it is stale by
     * definition and a reader cannot tell how old it is.
     */
    @Test
    void everyCurrentIndexRowCarriesItsOwnDate() throws IOException {
        var rows = currentIndexRows(rootOrSkip());

        assertFalse(rows.isEmpty(), "the Current table must parse, otherwise this gate is vacuous");
        var undated = rows.stream()
                .filter(row -> !CURRENT_ROW_WITH_DATE.matcher(row.state()).find())
                .map(row -> row.document() + " -> " + row.state())
                .toList();
        assertEquals(
                List.of(),
                undated,
                "every non-superseded INDEX row must state its own verification, measurement or "
                        + "supersession date in its State cell");
    }

    /**
     * DG-005: a document that writes its own {@code Verification date:} line and the INDEX row that
     * sends a reader to it must name the same day. The row takes the date from the document, never
     * the other way round: a later row hides the fact that the document itself has not been
     * re-verified.
     */
    @Test
    void agreesWithItsIndexRow() throws IOException {
        var root = rootOrSkip();
        var documents = root.resolve("docs").resolve("modernization");
        var disagreements = new ArrayList<String>();

        for (var row : currentIndexRows(root)) {
            var path = documents.resolve(row.document());
            if (!Files.isRegularFile(path)) {
                continue;
            }
            var stated = verificationDate(path);
            if (stated != null && !stated.equals(row.date())) {
                disagreements.add(row.document() + ": document says " + stated + ", INDEX says " + row.date());
            }
        }

        assertEquals(
                List.of(),
                disagreements,
                "an INDEX row must take its date from the document's own Verification date line");
    }

    /** The Current table's rows, as the document link target and the State cell. */
    private static List<IndexRow> currentIndexRows(Path root) throws IOException {
        var index = root.resolve("docs").resolve("modernization").resolve("INDEX.md");
        var rows = new ArrayList<IndexRow>();
        var inCurrentTable = false;
        for (var line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
            if (line.startsWith("## ")) {
                inCurrentTable = line.equals("## Current documents");
                continue;
            }
            if (!inCurrentTable || !line.startsWith("|")) {
                continue;
            }
            var cells = line.split("\\|", -1);
            if (cells.length < 4) {
                continue;
            }
            var link = cells[1].strip();
            var target = link.indexOf("](");
            if (!link.startsWith("[`") || target < 0) {
                continue;
            }
            var end = link.indexOf(')', target);
            if (end < 0) {
                continue;
            }
            rows.add(new IndexRow(link.substring(target + 2, end), cells[2].strip()));
        }
        return rows;
    }

    /** The date on a document's own {@code Verification date:} line, or {@code null} if it has none. */
    private static String verificationDate(Path document) throws IOException {
        for (var line : Files.readAllLines(document, StandardCharsets.UTF_8)) {
            var matcher = VERIFICATION_DATE.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** One row of {@code INDEX.md}'s Current table: the link target and the State cell. */
    private record IndexRow(String document, String state) {
        String date() {
            var matcher = DATE.matcher(state);
            return matcher.find() ? matcher.group() : null;
        }
    }

    private record CitedFile(int lineCount, Set<String> documents, Set<String> rows) {
    }

    private static Map<String, CitedFile> citedFiles(Path root) throws IOException {
        var index = javaFileIndex(root);
        var documents = readDocuments(root);
        var cited = new TreeMap<String, CitedFile>();

        for (var document : documents.entrySet()) {
            var number = 0;
            for (var text : document.getValue().split("\n", -1)) {
                number++;
                for (var citation : citationsIn(text)) {
                    if (citation.name() == null) {
                        continue;
                    }
                    var file = resolve(citation.name(), index);
                    if (file == null) {
                        continue;
                    }
                    var key = root.relativize(file).toString().replace('\\', '/');
                    var previous = cited.get(key);
                    var docs = previous == null
                            ? new LinkedHashSet<String>() : new LinkedHashSet<>(previous.documents());
                    var rows = previous == null
                            ? new LinkedHashSet<String>() : new LinkedHashSet<>(previous.rows());
                    docs.add(document.getKey());
                    rows.add(document.getKey() + ":" + number);
                    var lineCount = previous == null
                            ? readOrEmpty(file).size() : previous.lineCount();
                    cited.put(key, new CitedFile(lineCount, docs, rows));
                }
            }
        }
        return cited;
    }

    private static Map<String, Integer> readCanary() throws IOException {
        var recorded = new LinkedHashMap<String, Integer>();
        if (!Files.isRegularFile(CANARY)) {
            return recorded;
        }
        for (var line : Files.readAllLines(CANARY, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var parts = line.split("\t");
            if (parts.length == 2) {
                recorded.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
            }
        }
        return recorded;
    }

    private static List<String> readOrEmpty(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Path rootOrSkip() {
        var root = RepositoryEncodingTest.repositoryRoot();
        Assumptions.assumeTrue(root != null,
                "not a source checkout: the citation gate needs the repository root");
        return root;
    }

    private static Integer number(String value) {
        return value == null ? null : Integer.valueOf(value);
    }

    /** One parsed citation: the file it names, where it starts on the line, and its range. */
    record Citation(String name, int at, Integer from, Integer to, String token, boolean named) {
        int end() {
            return to == null ? from : to;
        }
    }
}
