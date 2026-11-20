package org.masymos.core.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The encoding rule of {@code AGENTS.md}, enforced: every text file in the checkout is UTF-8
 * without a BOM and uses LF line endings.
 *
 * <p>Why a test and not a formatter: {@code spotless} declares only the {@code java} and {@code pom}
 * formats, so a BOM or CRLF in a {@code .md} file passes every other gate. That is not a theory -
 * {@code developer-setup.md} carried a BOM and {@code legacy/migration-status.md} carried CRLF while
 * {@code mvn verify} was green. The rule lives in {@code AGENTS.md}; this test is what checks it,
 * and it runs as part of the routine build (QA-5).</p>
 *
 * <p>Exceptions follow {@code .gitattributes}: {@code *.ps1}, {@code *.cmd} and {@code *.bat} are
 * checked out with CRLF on purpose. {@code code/} (the legacy reference tree, which is not part of
 * this repository's sources) and {@code samples/} (read-only input fixtures) are out of scope, as is
 * anything under {@code target/} or under {@code .tools/} (a local, gitignored tool installation such
 * as the PSScriptAnalyzer module, whose own files are third-party). {@code .worktrees/} is out of
 * scope too: each worktree is a separate checkout with its own build, so walking it from here would
 * both duplicate this scan and let a worker's uncommitted file fail the orchestrator's build.</p>
 *
 * <p>A file is rejected for any of four things, because any one of them makes the bytes unusable as
 * text: a UTF-8 BOM, a NUL byte, a byte sequence that is not well-formed UTF-8, and a carriage
 * return. The UTF-8 check is a real decoder with {@code REPORT}, not a replacement-character scan: a
 * lenient decode silently rewrites the damage to U+FFFD and would pass.</p>
 *
 * <p>Those directories are <em>pruned</em> from the walk rather than filtered out per file, and
 * {@code .git} is among them for a reason that is not tidiness: a walk that descends into
 * {@code .git/worktrees/} dies with {@code AccessDeniedException} when a leftover of a removed
 * worktree is still held open by another process, and the gate then reports a path that is not a
 * source file at all. A directory that cannot be listed for any other reason is a failure naming the
 * path, never a silent skip: an unread region proves nothing.</p>
 */
class RepositoryEncodingTest {
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "java", "xml", "yml", "yaml", "json", "jsonl", "txt", "properties", "http", "csv", "tsv", "sql",
            "config", "psd1");
    private static final Set<String> CRLF_BY_GIT_ATTRIBUTES = Set.of("ps1", "cmd", "bat");
    private static final Set<String> NAMES_WITHOUT_EXTENSION = Set.of("Dockerfile", "AGENTS.md");
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".worktrees", ".tools", "target", "node_modules", "code", "samples");

    /**
     * Out of scope by path, not by name: the harvested corpus cache (CORP-3) holds third-party bytes
     * whose CRLF is the source's, and normalising them would break the sha256 that identifies them.
     * A name match would be too broad, because "files" and ".pool" occur elsewhere.
     */
    private static final Set<String> SKIPPED_PATHS = Set.of("corpus/files", "corpus/.pool");

    @Test
    void everyTextFileIsUtf8WithoutBomAndUsesLf() throws IOException {
        var root = repositoryRoot();
        Assumptions.assumeTrue(root != null, "not a source checkout: the encoding gate needs the repository root");

        var offenders = scan(root, descended -> {});

        if (!offenders.isEmpty()) {
            fail("the encoding rule is violated (AGENTS.md, 'Sub-agent briefs: the encoding rule')."
                    + " Rewrite each file as UTF-8 without BOM and with LF endings:"
                    + System.lineSeparator()
                    + String.join(System.lineSeparator(), offenders));
        }
    }

    @Test
    void theWalkPrunesDotGitInsteadOfEnteringItsWorktreeAdministration(@TempDir Path root) throws IOException {
        var administration = Files.createDirectories(root.resolve(".git").resolve("worktrees").resolve("cml-1-units"));
        Files.writeString(administration.resolve("gitdir"), "CRLF on purpose\r\n");
        var descended = new ArrayList<Path>();

        var offenders = scan(root, descended::add);

        assertEquals(List.of(), offenders, "nothing under .git is part of the checkout's text files");
        assertTrue(
                descended.stream().noneMatch(path -> path.toString().replace('\\', '/').contains(".git")),
                "the walk must not descend into .git at all: a locked directory there would abort the walk"
                        + " with an AccessDeniedException naming a path that is not a source file; descended into "
                        + descended);
    }

    @Test
    void aCrlfFileOutsideTheSkippedDirectoriesIsReported(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("notes.md"), "a\r\nb\n");

        var offenders = scan(root, descended -> {});

        assertEquals(
                1,
                offenders.size(),
                "the scan has to see ordinary text files, otherwise the .git assertion above is vacuously green: "
                        + offenders);
        assertTrue(offenders.get(0).contains("docs/notes.md"), "the offender is named by its relative path: " + offenders);
    }

    @Test
    void anInvalidUtf8SequenceIsReportedRatherThanReplaced(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("docs"));
        Files.write(root.resolve("docs").resolve("notes.md"), new byte[] {'a', (byte) 0xC3, (byte) 0x28, 'b'});

        var offenders = scan(root, descended -> {});

        assertEquals(1, offenders.size(), "a lenient decode rewrites the bad byte to U+FFFD and hides it: " + offenders);
        assertTrue(offenders.get(0).contains("invalid UTF-8 at byte 1"), "the offender names the byte: " + offenders);
    }

    @Test
    void aNulByteIsReportedEvenThoughItIsWellFormedUtf8(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("docs"));
        Files.write(root.resolve("docs").resolve("notes.md"), new byte[] {'a', 0, 'b'});

        var offenders = scan(root, descended -> {});

        assertEquals(1, offenders.size(), "NUL is valid UTF-8 and needs its own check: " + offenders);
        assertTrue(offenders.get(0).contains("NUL byte at 1"), "the offender names the byte: " + offenders);
    }

    @Test
    void tsvAndJsonlFilesAreInScope(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("corpus"));
        Files.writeString(root.resolve("corpus").resolve("manifest.tsv"), "a\r\nb\n");
        Files.writeString(root.resolve("corpus").resolve("rows.jsonl"), "{}\r\n");

        var offenders = scan(root, descended -> {});

        assertEquals(2, offenders.size(), "a data file is a text file and the gate must see it: " + offenders);
    }

    @Test
    void harvestedCorpusBytesStayOutOfScopeByPath(@TempDir Path root) throws IOException {
        var harvested = Files.createDirectories(root.resolve("corpus").resolve("files").resolve("biomodels"));
        Files.writeString(harvested.resolve("BIOMD0000000001.xml"), "a\r\nb\n");
        Files.writeString(root.resolve("corpus").resolve("manifest.tsv"), "a\tb\n");

        var offenders = scan(root, descended -> {});

        assertEquals(List.of(), offenders, "third-party bytes keep their CRLF and their sha256: " + offenders);
    }

    /**
     * Walks the checkout and returns one line per offender.
     *
     * <p>Skipped directories are pruned, not filtered per file: a filter that runs after the walk still
     * descends into them, and {@code Files.walk} then throws {@code AccessDeniedException} for a directory
     * it cannot list. That happened on 2026-11-17 with an empty, locked leftover of a removed git worktree
     * under {@code .git/worktrees/}, which turned the whole gate red for a cause unrelated to any source
     * file. A directory that cannot be listed for any other reason is deliberately a failure, not a silent
     * skip: this gate exists to prove the checkout is clean, and an unread region proves nothing.</p>
     *
     * @param root the checkout root
     * @param descended receives every directory the walk actually entered, for the pruning assertion
     */
    static List<String> scan(Path root, Consumer<Path> descended) throws IOException {
        var offenders = new ArrayList<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root)
                        && (SKIPPED_DIRECTORIES.contains(directory.getFileName().toString())
                                || SKIPPED_PATHS.contains(relative(root, directory)))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                descended.accept(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (isCheckedFile(root, file)) {
                    inspect(root, file, offenders);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException exception) {
                offenders.add(relative(root, path) + ": unreadable (" + exception + ")");
                return FileVisitResult.CONTINUE;
            }
        });
        offenders.sort(Comparator.naturalOrder());
        return offenders;
    }

    private static void inspect(Path root, Path path, List<String> offenders) {
        var relative = relative(root, path);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            offenders.add(relative + ": unreadable (" + exception.getMessage() + ")");
            return;
        }
        var problems = new ArrayList<String>();
        if (hasUtf8Bom(bytes)) {
            problems.add("UTF-8 BOM");
        }
        var nul = firstIndexOf(bytes, (byte) 0);
        if (nul >= 0) {
            problems.add("NUL byte at " + nul + " (not a text file)");
        }
        var invalid = firstInvalidUtf8Byte(bytes);
        if (invalid >= 0) {
            problems.add("invalid UTF-8 at byte " + invalid);
        }
        for (var index = 0; index < bytes.length; index++) {
            if (bytes[index] == '\r') {
                problems.add("CR at byte " + index + " (CRLF or a bare CR; use LF)");
                break;
            }
        }
        if (!problems.isEmpty()) {
            offenders.add(relative + ": " + String.join("; ", problems));
        }
    }

    private static int firstIndexOf(byte[] bytes, int value) {
        for (var index = 0; index < bytes.length; index++) {
            if ((bytes[index] & 0xFF) == value) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The index of the first byte that is not part of a well-formed UTF-8 sequence, or {@code -1}.
     * Uses the platform decoder with {@code REPORT}: a decoder that replaces the bad bytes with U+FFFD
     * makes an invalid file look valid, which is the defect this check exists for.
     */
    private static int firstInvalidUtf8Byte(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var input = ByteBuffer.wrap(bytes);
        var output = CharBuffer.allocate(1024);
        while (true) {
            var result = decoder.decode(input, output, true);
            if (result.isError()) {
                return input.position();
            }
            if (result.isOverflow()) {
                output.clear();
                continue;
            }
            break;
        }
        while (true) {
            var result = decoder.flush(output);
            if (result.isError()) {
                return input.position();
            }
            if (result.isOverflow()) {
                output.clear();
                continue;
            }
            return -1;
        }
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
    }

    private static boolean isCheckedFile(Path root, Path path) {
        for (var segment : root.relativize(path)) {
            if (SKIPPED_DIRECTORIES.contains(segment.toString())) {
                return false;
            }
        }
        var name = path.getFileName().toString();
        if (NAMES_WITHOUT_EXTENSION.contains(name)) {
            return true;
        }
        var extension = extensionOf(name);
        return TEXT_EXTENSIONS.contains(extension) && !CRLF_BY_GIT_ATTRIBUTES.contains(extension);
    }

    private static String extensionOf(String name) {
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    /**
     * The checkout root: the first ancestor holding a {@code samples} directory and the
     * {@code masymos-modern} build, found either as {@code pom.xml} at that same level or as
     * {@code modern/pom.xml}; {@code null} when the tests run outside a checkout.
     *
     * <p>Why the build and not {@code AGENTS.md} plus {@code modern/pom.xml}, which was the older
     * marker pair: the sources are published into a second repository that carries no
     * {@code AGENTS.md}, so a gate keyed on it would skip there while looking like it ran. The
     * artifact id tells this reactor apart from any {@code pom.xml} that happens to sit above it,
     * and both layouts resolve to the same directory - the publish layout keeps the parent pom at
     * the root (case a), this checkout keeps it under {@code modern/} (case b). The six-level bound
     * and the {@code null} result are unchanged, so every caller still skips with its own
     * assumption instead of failing.</p>
     */
    static Path repositoryRoot() {
        var candidate = Path.of("").toAbsolutePath();
        for (var depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("samples")) && isModernParentPom(candidate)) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }

    private static boolean isModernParentPom(Path candidate) {
        var atRoot = candidate.resolve("pom.xml");
        if (Files.isRegularFile(atRoot) && declaresModernParentPom(atRoot)) {
            return true;
        }
        var underModern = candidate.resolve("modern").resolve("pom.xml");
        return Files.isRegularFile(underModern) && declaresModernParentPom(underModern);
    }

    private static boolean declaresModernParentPom(Path pom) {
        try {
            return Files.readString(pom).contains("<artifactId>masymos-modern</artifactId>");
        } catch (IOException exception) {
            return false;
        }
    }
}
