package org.masymos.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "maintenance",
        description = "Run explicit MaSyMoS maintenance operations.",
        subcommands = {
                SedmlLinksCommand.class,
                MaintenanceAnnotationsCommand.class,
                UnitsBackfillCommand.class
        }
)
public class MaintenanceCommand implements Callable<Integer> {
    @ParentCommand
    private MaSyMoSCommand rootCommand;

    @Spec
    private CommandSpec spec;

    String apiUrl() {
        return rootCommand == null ? "http://localhost:8080" : rootCommand.apiUrl;
    }

    /**
     * A bare group command names no operation to run. Printing usage and exiting 0 made a mistyped
     * script look like a completed run, so the usage goes to the error stream and the exit code is the
     * usage code, which a caller can tell from success and from a service failure.
     */
    @Override
    public Integer call() {
        var commandLine = spec.commandLine();
        commandLine.usage(commandLine.getErr());
        return CommandLine.ExitCode.USAGE;
    }
}
