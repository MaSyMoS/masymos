package org.masymos.cli;

import java.net.http.HttpClient;
import picocli.CommandLine.Command;
import tools.jackson.databind.ObjectMapper;

@Command(
        name = "combine",
        mixinStandardHelpOptions = true,
        description = "Index COMBINE archives through the MaSyMoS service."
)
public class IndexCombineCommand extends ServiceIndexModelCommand {
    public IndexCombineCommand() {
        super();
    }

    IndexCombineCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        super(httpClient, objectMapper);
    }

    IndexCombineCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        super(requestSender, objectMapper);
    }

    @Override
    String endpointPath() {
        return "/api/v1/indexes/combine";
    }
}
