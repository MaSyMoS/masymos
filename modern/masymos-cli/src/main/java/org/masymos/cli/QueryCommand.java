package org.masymos.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "query",
        description = "Run MaSyMoS queries.",
        subcommands = {
                QueryModelsCommand.class,
                QueryAnnotationsCommand.class,
                QueryPersonsCommand.class,
                QueryPublicationsCommand.class
        }
)
public class QueryCommand {
    @ParentCommand
    private MaSyMoSCommand rootCommand;

    String apiUrl() {
        return rootCommand == null ? "http://localhost:8080" : rootCommand.apiUrl;
    }
}
