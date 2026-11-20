package org.masymos.core.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

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
