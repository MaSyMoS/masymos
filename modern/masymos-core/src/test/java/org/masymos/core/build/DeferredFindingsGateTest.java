package org.masymos.core.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

/**
 * The deferred-findings gate of {@code verify}: a deferred review finding is a row with a stable id
 * in a plan's {@code ## Deferred findings} table, written at review time, and a count of deferred
 * findings without the rows behind it fails the build.
 *
 * <p>Why a test and not {@code exec-maven-plugin}: the same reason as {@link RepositoryEncodingTest}
 * (QA-5) and {@link PowerShellScriptAnalyzerTest} (QA-8) - the reactor has to stay runnable on a host
 * without PowerShell. A host without {@code pwsh} skips with a stated assumption.</p>
 *
 * <p>A green exit is not proof on its own: the summary line is asserted as well, so a run that
 * reported nothing cannot pass, and a tree whose documents were deleted cannot pass by having no
 * table to complain about. The rule the script enforces lives in its docstring, which is where a
 * session that meets a red gate reads it.</p>
 */
class DeferredFindingsGateTest {
    private static final String SCRIPT = "lint-deferred-findings.ps1";
    private static final Pattern SUMMARY =
            Pattern.compile("deferred findings: (\\d+) rows, (\\d+) open, (\\d+) problem");

    @Test
    void everyDeferredFindingIsARowAndEveryOpenRowIsNamedInTheHandoff() throws IOException, InterruptedException {
        var root = RepositoryEncodingTest.repositoryRoot();
        Assumptions.assumeTrue(
                root != null, "not a source checkout: the deferred-findings gate needs the repository root");
        Assumptions.assumeTrue(
                isPowerShellSevenAvailable(),
                "pwsh is not installed: run scripts/lint-deferred-findings.ps1 by hand");

        var output = run(root);
        assertTrue(
                output.contains("0 problem"),
                "the gate ran without reporting a verdict; refusing a vacuous pass:" + System.lineSeparator() + output);
        assertNonZeroRows(output);
    }

    @Test
    void theOpenRowsCanBeListedWithoutRunningTheGate() throws IOException, InterruptedException {
        var root = RepositoryEncodingTest.repositoryRoot();
        Assumptions.assumeTrue(
                root != null, "not a source checkout: the deferred-findings gate needs the repository root");
        Assumptions.assumeTrue(
                isPowerShellSevenAvailable(),
                "pwsh is not installed: run scripts/lint-deferred-findings.ps1 --open by hand");

        var output = run(root, "-Open");
        assertTrue(
                !output.contains("deferred findings:"),
                "the read-only mode must not run the gate; it only lists the open rows:"
                        + System.lineSeparator()
                        + output);
    }

    /**
     * GAP04-2: the gate reads the reason column. A row whose why cell is the label {@code minor} used
     * to pass every check; it must now fail with the label message, on a temporary tree so the
     * repository's own rows are never the subject. The rest of the tree is minimal but complete: the
     * plan table, a {@code where} file that exists, an empty ledger and a handoff naming the open id,
     * so the only finding left is the label.
     */
    @Test
    void aRowWhoseWhyCellIsALabelFailsTheGate(@TempDir Path root) throws IOException, InterruptedException {
        Assumptions.assumeTrue(
                isPowerShellSevenAvailable(),
                "pwsh is not installed: run scripts/lint-deferred-findings.ps1 by hand");

        var plans = Files.createDirectories(root.resolve("docs").resolve("superpowers").resolve("plans"));
        var modernization = Files.createDirectories(root.resolve("docs").resolve("modernization"));
        Files.writeString(root.resolve("row.md"), "the line the where cell names\n", StandardCharsets.UTF_8);
        Files.writeString(plans.resolve("tmp-plan.md"), """
                # Temporary plan

                ## Deferred findings

                | id | finding | where | why deferred (verified consequence) | closes in | status |
                | --- | --- | --- | --- | --- | --- |
                | TST-1 | a finding | row.md:1 | minor | needs triage | open |
                """, StandardCharsets.UTF_8);
        Files.writeString(modernization.resolve("migration-plan.md"), "", StandardCharsets.UTF_8);
        Files.writeString(modernization.resolve("handoff.md"), """
                # Handoff

                ## Open deferred findings

                - `TST-1` - needs triage
                """, StandardCharsets.UTF_8);

        var output = runGateOver(root);

        assertTrue(
                output.contains("is a label, not a consequence"),
                "the gate must reject a label in the why-deferred cell:" + System.lineSeparator() + output);
    }

    /**
     * Runs the checkout's gate against another root and requires it to fail; returns its output. The
     * script lives in the checkout, so a temporary tree carries only the documents the gate reads.
     */
    private static String runGateOver(Path root) throws IOException, InterruptedException {
        var checkout = RepositoryEncodingTest.repositoryRoot();
        Assumptions.assumeTrue(checkout != null, "not a source checkout: the gate script has nowhere to live");
        Assumptions.assumeTrue(
                isPowerShellSevenAvailable(),
                "pwsh is not installed: run scripts/lint-deferred-findings.ps1 by hand");
        var command = List.of(
                "pwsh", "-NoProfile", "-NonInteractive",
                "-File", checkout.resolve("scripts").resolve(SCRIPT).toString(),
                "-Root", root.toString());
        var log = Files.createTempFile("lint-deferred-findings-label", ".log");
        try {
            var process = new ProcessBuilder(command)
                    .directory(checkout.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail("scripts/" + SCRIPT + " did not finish within five minutes");
            }
            var output = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(
                    process.exitValue() != 0,
                    "the gate must exit non-zero, but it exited 0:"
                            + System.lineSeparator()
                            + output);
            return output;
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /**
     * The gate must find rows: a tree whose plans kept their headings but lost their rows prints
     * {@code 0 rows, 0 open, 0 problem} and used to pass. The assertion is parsed from the summary
     * line, not from {@code contains}, so a changed summary shape fails rather than silently passing.
     */
    private static void assertNonZeroRows(String output) {
        var matcher = SUMMARY.matcher(output);
        assertTrue(
                matcher.find(),
                "the gate printed no summary line:" + System.lineSeparator() + output);
        var rows = Integer.parseInt(matcher.group(1));
        assertTrue(
                rows >= 1,
                "the gate found no deferred-findings row; a table that lost its rows must not pass:"
                        + System.lineSeparator()
                        + output);
    }

    /** Copies the checkout's gate into a temporary tree, whose root the script finds by `.git`. */
    private static void copyGateScript(Path root) throws IOException {
        var checkout = RepositoryEncodingTest.repositoryRoot();
        Assumptions.assumeTrue(checkout != null, "not a source checkout: the gate script has nowhere to live");
        var scripts = Files.createDirectories(root.resolve("scripts"));
        Files.copy(checkout.resolve("scripts").resolve(SCRIPT), scripts.resolve(SCRIPT));
        Files.createDirectories(root.resolve(".git"));
    }

    /**
     * TEST-005: the class's own promise is that a vacuous pass is impossible, and a tree with no
     * deferred table is the vacuous case. The gate exits 0 there by design - a wave may have no
     * deferred finding - so the row-count assertion is what must fire, and this test proves it does.
     */
    @Test
    void aTreeWhosePlansHaveNoDeferredTableFails(@TempDir Path root) throws IOException, InterruptedException {
        Assumptions.assumeTrue(
                isPowerShellSevenAvailable(),
                "pwsh is not installed: run scripts/lint-deferred-findings.ps1 by hand");
        copyGateScript(root);
        var plans = Files.createDirectories(root.resolve("docs").resolve("superpowers").resolve("plans"));
        var modernization = Files.createDirectories(root.resolve("docs").resolve("modernization"));
        Files.writeString(plans.resolve("empty-plan.md"), "# Empty plan\n\nNo deferred rows here.\n", StandardCharsets.UTF_8);
        Files.writeString(modernization.resolve("migration-plan.md"), "", StandardCharsets.UTF_8);
        Files.writeString(
                modernization.resolve("handoff.md"), "# Handoff\n\n## Open deferred findings\n", StandardCharsets.UTF_8);

        var output = run(root);

        var fired = assertThrows(
                AssertionFailedError.class,
                () -> assertNonZeroRows(output),
                "a tree whose plans lost their deferred rows must fail the row-count assertion");
        assertTrue(
                fired.getMessage().contains("no deferred-findings row"),
                "the assertion must name the empty row count: " + fired.getMessage());
    }

    /**
     * GAP04-3: a backticked id anywhere in the handoff's open section is a reference the document
     * makes by name, so a closed id named mid-sentence is rejected. A bare mid-line id is prose and
     * is not checked, which is why this test writes the reference in backticks.
     */
    @Test
    void theHandoffMayNotNameAClosedRowMidSentence(@TempDir Path root) throws IOException, InterruptedException {
        Assumptions.assumeTrue(
                isPowerShellSevenAvailable(),
                "pwsh is not installed: run scripts/lint-deferred-findings.ps1 by hand");
        var plans = Files.createDirectories(root.resolve("docs").resolve("superpowers").resolve("plans"));
        var modernization = Files.createDirectories(root.resolve("docs").resolve("modernization"));
        Files.writeString(root.resolve("row.md"), "the line the where cell names\n", StandardCharsets.UTF_8);
        Files.writeString(plans.resolve("tmp-plan.md"), """
                # Temporary plan

                ## Deferred findings

                | id | finding | where | why deferred (verified consequence) | closes in | status |
                | --- | --- | --- | --- | --- | --- |
                | TST-1 | a finding | row.md:1 | the second row would hide a closed id if it is left | needs triage | open |
                """, StandardCharsets.UTF_8);
        Files.writeString(modernization.resolve("migration-plan.md"), "", StandardCharsets.UTF_8);
        Files.writeString(modernization.resolve("handoff.md"), """
                # Handoff

                ## Open deferred findings

                - `TST-1` - needs triage

                The earlier row was closed `X-9` on 2026-11-20.
                """, StandardCharsets.UTF_8);

        var output = runGateOver(root);

        assertTrue(
                output.contains("mid-sentence in backticks"),
                "the gate must reject a backticked closed id in the handoff's open section:"
                        + System.lineSeparator()
                        + output);
    }

    /**
     * Whether the checkout carries the documents the gate reads: its plan directory, the migration
     * plan and the handoff carrying the open ids. A checkout published without them has no table for
     * the gate to judge, so {@link #run} skips there with that stated reason instead of letting the
     * script fail for a missing document; a vacuous pass stays impossible, because a tree that has
     * the documents still has to report {@code 0 problem}. This checkout has all three, so the gate
     * runs here.
     */
    private static boolean carriesMigrationDocuments(Path root) {
        return Files.isDirectory(root.resolve("docs").resolve("superpowers").resolve("plans"))
                && Files.isRegularFile(root.resolve("docs").resolve("modernization").resolve("migration-plan.md"))
                && Files.isRegularFile(root.resolve("docs").resolve("modernization").resolve("handoff.md"));
    }

    /** Runs the gate from the repository root and requires exit 0; returns its combined output. */
    private static String run(Path root, String... arguments) throws IOException, InterruptedException {
        Assumptions.assumeTrue(
                carriesMigrationDocuments(root),
                "no migration documents: scripts/lint-deferred-findings.ps1 has no table to check in a checkout"
                        + " without docs/superpowers/plans, docs/modernization/migration-plan.md and"
                        + " docs/modernization/handoff.md");
        var command = new ArrayList<String>();
        command.add("pwsh");
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-File");
        command.add(root.resolve("scripts").resolve(SCRIPT).toString());
        command.addAll(List.of(arguments));

        var log = Files.createTempFile("lint-deferred-findings", ".log");
        try {
            var process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail("scripts/" + SCRIPT + " did not finish within five minutes");
            }
            var output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(
                    0,
                    process.exitValue(),
                    "the deferred-findings gate is red, or it could not run:" + System.lineSeparator() + output);
            return output;
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static boolean isPowerShellSevenAvailable() {
        try {
            var probe = new ProcessBuilder(
                            "pwsh", "-NoProfile", "-NonInteractive", "-Command", "$PSVersionTable.PSVersion.Major")
                    .redirectErrorStream(true)
                    .start();
            var version = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return probe.waitFor(60, TimeUnit.SECONDS) && "7".equals(version);
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
