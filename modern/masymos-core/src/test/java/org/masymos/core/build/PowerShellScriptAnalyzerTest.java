package org.masymos.core.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
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
    /**
     * The gate's own summary line, anchored so {@code 10 finding(s)} cannot satisfy a check for
     * {@code 0 finding(s)}: {@code TEST-006} measured that a substring test does exactly that.
     */
    private static final Pattern SUMMARY = Pattern.compile(
            "^lint-powershell: analyzed (\\d+) script\\(s\\) with PSScriptAnalyzer ([\\w.\\-]+); (\\d+) finding\\(s\\)$",
            Pattern.MULTILINE);

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
            var summary = summary(output);
            assertTrue(
                    summary.scripts() >= 1,
                    "a summary that analysed no script means the gate did not run; refusing a vacuous pass:"
                            + System.lineSeparator()
                            + output);
            assertEquals(
                    0,
                    summary.findings(),
                    "the summary reports findings, so the gate did not pass:" + System.lineSeparator() + output);
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /**
     * The assertion above is only worth its name if the check it replaced would have passed a red
     * run. {@code TEST-006}: {@code output.contains("0 finding")} is true for
     * {@code "; 10 finding(s)"}, so the old check could not fail on a ten-finding gate. The summary is
     * therefore parsed, and this test pins the parse against exactly that output.
     */
    @Test
    void theSummaryFindingCountIsZeroAndNotASubstringMatch() {
        var withTenFindings = String.join(
                System.lineSeparator(),
                "scripts/harvest-corpus.ps1:12: Warning PSAvoidUsingCmdletAliases: 'cd' is an alias.",
                "lint-powershell: analyzed 12 script(s) with PSScriptAnalyzer 1.24.0; 10 finding(s)");
        var green = "lint-powershell: analyzed 12 script(s) with PSScriptAnalyzer 1.24.0; 0 finding(s)";

        assertTrue(
                withTenFindings.contains("0 finding"),
                "the substring the old check used must match this output, or this test proves nothing");

        var red = summary(withTenFindings);
        assertEquals(10, red.findings(), "the parse must read the finding count, not a substring of it");
        assertEquals(12, red.scripts(), "the parse must read the script count");
        assertEquals("1.24.0", red.analyzerVersion(), "the parse must read the analyzer version");

        var passed = summary(green);
        assertEquals(0, passed.findings(), "a green summary reports zero findings");
        assertEquals(12, passed.scripts(), "the script count is read the same way on both paths");
    }

    /** The gate's summary line, or a failure naming the output that carried none. */
    static Summary summary(String output) {
        var matcher = SUMMARY.matcher(output);
        if (!matcher.find()) {
            throw new AssertionError(
                    "the gate printed no 'lint-powershell: analyzed ...' summary, so it did not run:"
                            + System.lineSeparator()
                            + output);
        }
        return new Summary(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2),
                Integer.parseInt(matcher.group(3)));
    }

    /** What the gate's summary line states. */
    record Summary(int scripts, String analyzerVersion, int findings) {
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
