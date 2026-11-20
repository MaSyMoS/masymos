package org.masymos.core.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The script gate of {@code verify} (QA-8): every file under {@code scripts/} must pass
 * PSScriptAnalyzer.
 *
 * <p>Why a test and not {@code exec-maven-plugin}: the same reason as {@link RepositoryEncodingTest}
 * (QA-5), the reactor has to stay runnable on a host without PowerShell. A host without {@code pwsh}
 * skips with a stated assumption. A host with {@code pwsh} but without the PSScriptAnalyzer module
 * fails, because a lint gate that cannot run proves nothing: the message names the install command.</p>
 *
 * <p>Measured value, not a theory: the gate found two variables that were assigned and never read in
 * {@code harvest-corpus.ps1}, the shared RNG left over from the per-cell draw rewrite and a format
 * extension switch whose result was never used. It also proves the {@code #Requires -Version 7.0}
 * claim against the 7.0 syntax target, so a script cannot silently assume the host it is run on.</p>
 */
class PowerShellScriptAnalyzerTest {
    @Test
    void everyScriptUnderScriptsPassesPSScriptAnalyzer() throws IOException, InterruptedException {
        var root = RepositoryEncodingTest.repositoryRoot();
        Assumptions.assumeTrue(root != null, "not a source checkout: the script gate needs the repository root");
        Assumptions.assumeTrue(
                Files.isRegularFile(root.resolve("scripts").resolve("lint-powershell.ps1")),
                "this checkout carries no scripts directory: the script gate runs in the workspace repository,"
                        + " which is where the scripts that are linted live");
        Assumptions.assumeTrue(
                isPowerShellSevenAvailable(), "pwsh is not installed: run scripts/lint-powershell.ps1 by hand");

        var log = Files.createTempFile("lint-powershell", ".log");
        try {
            var process = new ProcessBuilder(
                            "pwsh",
                            "-NoProfile",
                            "-NonInteractive",
                            "-File",
                            root.resolve("scripts").resolve("lint-powershell.ps1").toString())
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail("scripts/lint-powershell.ps1 did not finish within five minutes");
            }
            var output = Files.readString(log, StandardCharsets.UTF_8);
            assertEquals(
                    0,
                    process.exitValue(),
                    "PSScriptAnalyzer reported findings, or the gate could not run:" + System.lineSeparator() + output);
            assertTrue(
                    output.contains("0 finding"),
                    "a green exit without a summary means the gate did not run; refusing a vacuous pass:"
                            + System.lineSeparator()
                            + output);
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
