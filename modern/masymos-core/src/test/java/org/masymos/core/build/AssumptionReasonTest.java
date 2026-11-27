package org.masymos.core.build;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Every assumption in a test source states why it skips, or the reactor summary cannot be read.
 *
 * <p>{@code TEST4-1} filed the gap: {@code assumeTrue(Files.isRegularFile(sample))} with no message
 * looks exactly like a passing test in the summary, so a checkout without the sample corpus reports
 * green while the assertion behind the sample never ran. {@code TEST-004} measured eight such calls
 * in the four sample-dependent parser tests; the remedy was a message per call, and this class is
 * the check that keeps the ninth call from arriving without one.</p>
 *
 * <p><b>What this checks, and what it does not.</b> It reads the sources as text, so it can see
 * whether an assumption states <em>something</em>; it cannot see whether the statement names the
 * missing path or the lane that carries it. That half stays prose, which is what
 * {@code AGENTS.md} asks a reviewer to read. Comments are blanked before the scan so a call quoted
 * in prose or javadoc is not mistaken for one, but a call written as a string literal is still read
 * as a call that states nothing - no such occurrence exists today, and the rule is not worth a Java
 * parser.</p>
 *
 * <p>A call is accepted when it has a second argument at its own parenthesis level: a message, a
 * supplier of one, or a concatenation. String, character and text-block literals are skipped while
 * counting, so a message that contains a comma or a parenthesis cannot make a bounded call look
 * unbounded.</p>
 */
class AssumptionReasonTest {

    /** {@code assumeTrue(...)} and {@code assumeFalse(...)}, qualified or statically imported. */
    private static final Pattern ASSUMPTION = Pattern.compile("\\bassume(?:True|False)\\s*\\(");

    @Test
    void everyAssumptionStatesItsReason() throws IOException {
        var root = RepositoryEncodingTest.repositoryRoot();
        Assumptions.assumeTrue(root != null,
                "not a source checkout: the assumption gate needs the repository root");

        var sources = testSources(root.resolve("modern"));
        assertTrue(!sources.isEmpty(),
                "no test source found under " + root.resolve("modern") + ", so this gate proves nothing");

        var withoutReason = new ArrayList<String>();
        for (var source : sources) {
            withoutReason.addAll(assumptionsWithoutReason(source, root));
        }
        assertTrue(withoutReason.isEmpty(),
                "every assumption must state why it skips; these state nothing: " + withoutReason);
    }

    private static List<Path> testSources(Path modern) throws IOException {
        var modules = new ArrayList<Path>();
        try (Stream<Path> children = Files.list(modern)) {
            children.filter(Files::isDirectory).forEach(modules::add);
        }
        var sources = new ArrayList<Path>();
        for (var module : modules) {
            var testRoot = module.resolve("src").resolve("test").resolve("java");
            if (!Files.isDirectory(testRoot)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(testRoot)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .forEach(sources::add);
            }
        }
        sources.sort(null);
        return sources;
    }

    private static List<String> assumptionsWithoutReason(Path source, Path root) throws IOException {
        var text = Files.readString(source);
        var commented = commentedPositions(text);
        var found = new ArrayList<String>();
        var matcher = ASSUMPTION.matcher(text);
        while (matcher.find()) {
            var open = matcher.end() - 1;
            if (commented[matcher.start()]) {
                continue;
            }
            var call = call(text, open);
            var where = root.relativize(source).toString().replace('\\', '/') + ":" + lineOf(text, open);
            if (call.close() < 0) {
                found.add(where + " (the call does not close: this gate cannot read it)");
            } else if (call.arguments() < 2) {
                found.add(where);
            }
        }
        return found;
    }

    /**
     * Which characters sit inside a comment. Literals are tracked so a {@code //} or {@code /*} in a
     * message is not read as the start of one; a text block is skipped as a unit for the same reason.
     */
    private static boolean[] commentedPositions(String text) {
        var commented = new boolean[text.length()];
        var state = State.CODE;
        var escaped = false;
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            switch (state) {
                case LINE_COMMENT -> {
                    if (character == '\n') {
                        state = State.CODE;
                    } else {
                        commented[index] = true;
                    }
                }
                case BLOCK_COMMENT -> {
                    commented[index] = true;
                    if (text.startsWith("*/", index)) {
                        commented[index + 1] = true;
                        index++;
                        state = State.CODE;
                    }
                }
                case STRING, CHARACTER -> {
                    if (escaped) {
                        escaped = false;
                    } else if (character == '\\') {
                        escaped = true;
                    } else if (state == State.STRING && character == '"') {
                        state = State.CODE;
                    } else if (state == State.CHARACTER && character == '\'') {
                        state = State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (text.startsWith("\"\"\"", index)) {
                        index += 2;
                        state = State.CODE;
                    }
                }
                case CODE -> {
                    if (text.startsWith("//", index)) {
                        commented[index] = true;
                        commented[index + 1] = true;
                        index++;
                        state = State.LINE_COMMENT;
                    } else if (text.startsWith("/*", index)) {
                        commented[index] = true;
                        commented[index + 1] = true;
                        index++;
                        state = State.BLOCK_COMMENT;
                    } else if (text.startsWith("\"\"\"", index)) {
                        index += 2;
                        state = State.TEXT_BLOCK;
                    } else if (character == '"') {
                        state = State.STRING;
                    } else if (character == '\'') {
                        state = State.CHARACTER;
                    }
                }
            }
        }
        return commented;
    }

    /**
     * The parenthesis that closes {@code open}, and how many arguments the call has. The scan walks
     * the call's own text, so nesting, a comma inside a message and a bracket inside a literal all
     * count the way Java counts them.
     */
    private static Call call(String text, int open) {
        var depth = 0;
        var commas = 0;
        var state = State.CODE;
        var escaped = false;
        for (var index = open; index < text.length(); index++) {
            var character = text.charAt(index);
            switch (state) {
                case STRING, CHARACTER -> {
                    if (escaped) {
                        escaped = false;
                    } else if (character == '\\') {
                        escaped = true;
                    } else if (state == State.STRING && character == '"') {
                        state = State.CODE;
                    } else if (state == State.CHARACTER && character == '\'') {
                        state = State.CODE;
                    }
                }
                case TEXT_BLOCK -> {
                    if (text.startsWith("\"\"\"", index)) {
                        state = State.CODE;
                        index += 2;
                    }
                }
                case CODE -> {
                    if (text.startsWith("\"\"\"", index)) {
                        state = State.TEXT_BLOCK;
                        index += 2;
                    } else if (character == '"') {
                        state = State.STRING;
                    } else if (character == '\'') {
                        state = State.CHARACTER;
                    } else if (character == '(') {
                        depth++;
                    } else if (character == ')') {
                        depth--;
                        if (depth == 0) {
                            return new Call(index, commas + 1);
                        }
                    } else if (character == ',') {
                        if (depth == 1) {
                            commas++;
                        }
                    }
                }
                default -> {
                    // A comment cannot open inside a call that already started.
                }
            }
        }
        return new Call(-1, -1);
    }

    private static int lineOf(String text, int index) {
        var line = 1;
        for (var position = 0; position < index && position < text.length(); position++) {
            if (text.charAt(position) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Where the closing parenthesis is, and how many arguments precede it. */
    private record Call(int close, int arguments) {
    }

    private enum State {
        CODE,
        STRING,
        CHARACTER,
        TEXT_BLOCK,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
