package org.masymos.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "index",
        description = "Index models through the MaSyMoS service.",
        subcommands = {
                IndexSbmlCommand.class,
                IndexCellmlCommand.class,
                IndexSedmlCommand.class,
                IndexOwlCommand.class,
                IndexCombineCommand.class
        }
)
public class IndexCommand {
    @ParentCommand
    private MaSyMoSCommand rootCommand;

    String apiUrl() {
        return rootCommand == null ? "http://localhost:8080" : rootCommand.apiUrl;
    }
}
