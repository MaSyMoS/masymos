package org.masymos.cli;

import java.net.http.HttpClient;
import picocli.CommandLine.Command;
import tools.jackson.databind.ObjectMapper;

@Command(
        name = "owl",
        mixinStandardHelpOptions = true,
        description = "Index OWL ontologies through the MaSyMoS service."
)
public class IndexOwlCommand extends ServiceIndexModelCommand {
    public IndexOwlCommand() {
        super();
    }

    IndexOwlCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        super(httpClient, objectMapper);
    }

    IndexOwlCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        super(requestSender, objectMapper);
    }

    @Override
    String endpointPath() {
        return "/api/v1/indexes/owl";
    }
}
