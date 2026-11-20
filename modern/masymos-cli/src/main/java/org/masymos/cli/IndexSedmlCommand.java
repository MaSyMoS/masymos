package org.masymos.cli;

import java.net.http.HttpClient;
import picocli.CommandLine.Command;
import tools.jackson.databind.ObjectMapper;

@Command(
        name = "sedml",
        mixinStandardHelpOptions = true,
        description = "Index SED-ML models through the MaSyMoS service."
)
public class IndexSedmlCommand extends ServiceIndexModelCommand {
    public IndexSedmlCommand() {
        super();
    }

    IndexSedmlCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        super(httpClient, objectMapper);
    }

    IndexSedmlCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        super(requestSender, objectMapper);
    }

    @Override
    String endpointPath() {
        return "/api/v1/indexes/sedml";
    }
}
