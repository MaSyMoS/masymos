package org.masymos.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "masymos",
        mixinStandardHelpOptions = true,
        version = "masymos 2.0.0-SNAPSHOT",
        description = "Modern MaSyMoS command line interface.",
        subcommands = {
                IndexCommand.class,
                QueryCommand.class,
                MaintenanceCommand.class,
                DeleteCommand.class,
                StatsCommand.class,
                AnalyzeCommand.class
        }
)
public class MaSyMoSCommand implements Callable<Integer> {
    @Option(names = "--api-url", description = "Base URL of the MaSyMoS service.")
    String apiUrl = "http://localhost:8080";

    @Override
    public Integer call() {
        System.out.printf("MaSyMoS service: %s%n", apiUrl);
        return 0;
    }
}
