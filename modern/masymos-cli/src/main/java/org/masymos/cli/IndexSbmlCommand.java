package org.masymos.cli;

import java.net.http.HttpClient;
import picocli.CommandLine.Command;
import tools.jackson.databind.ObjectMapper;

@Command(
        name = "sbml",
        mixinStandardHelpOptions = true,
        description = "Index SBML models through the MaSyMoS service."
)
public class IndexSbmlCommand extends ServiceIndexModelCommand {
    public IndexSbmlCommand() {
        super();
    }

    IndexSbmlCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        super(httpClient, objectMapper);
    }

    IndexSbmlCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        super(requestSender, objectMapper);
    }

    @Override
    String endpointPath() {
        return "/api/v1/indexes/sbml";
    }
}
