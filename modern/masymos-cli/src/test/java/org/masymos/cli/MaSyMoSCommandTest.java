package org.masymos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class MaSyMoSCommandTest {
    @Test
    void registersQueryModelsSubcommand() {
        var out = new StringWriter();
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("query", "models", "--help");

        assertEquals(0, exitCode);
    }

    @Test
    void registersIndexSbmlSubcommand() {
        var out = new StringWriter();
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("index", "sbml", "--help");

        assertEquals(0, exitCode);
    }

    @Test
    void registersAdditionalIndexSubcommands() {
        assertIndexHelp("cellml");
        assertIndexHelp("sedml");
        assertIndexHelp("owl");
    }

    @Test
    void registersSedmlMaintenanceSubcommand() {
        var out = new StringWriter();
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("maintenance", "sedml-links", "--help");

        assertEquals(0, exitCode);
    }

    private void assertIndexHelp(String subcommand) {
        var out = new StringWriter();
        var commandLine = new CommandLine(new MaSyMoSCommand());
        commandLine.setOut(new PrintWriter(out));

        int exitCode = commandLine.execute("index", subcommand, "--help");

        assertEquals(0, exitCode);
    }
}
