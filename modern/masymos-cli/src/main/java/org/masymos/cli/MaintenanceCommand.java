package org.masymos.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "maintenance",
        description = "Run explicit MaSyMoS maintenance operations.",
        subcommands = {
                SedmlLinksCommand.class,
                MaintenanceAnnotationsCommand.class,
                UnitsBackfillCommand.class
        }
)
public class MaintenanceCommand {
    @ParentCommand
    private MaSyMoSCommand rootCommand;

    String apiUrl() {
        return rootCommand == null ? "http://localhost:8080" : rootCommand.apiUrl;
    }
}
