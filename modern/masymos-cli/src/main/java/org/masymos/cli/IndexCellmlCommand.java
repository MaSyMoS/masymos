package org.masymos.cli;

import java.net.http.HttpClient;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.ObjectMapper;

@Command(
        name = "cellml",
        mixinStandardHelpOptions = true,
        description = "Index CellML models through the MaSyMoS service."
)
public class IndexCellmlCommand extends ServiceIndexModelCommand {
    @Option(
            names = "--external-cmeta",
            defaultValue = "none",
            description = "External CellML CMeta source: none or pmr. Defaults to ${DEFAULT-VALUE}."
    )
    private String externalCmeta;

    public IndexCellmlCommand() {
        super();
    }

    IndexCellmlCommand(HttpClient httpClient, ObjectMapper objectMapper) {
        super(httpClient, objectMapper);
    }

    IndexCellmlCommand(HttpRequestSender requestSender, ObjectMapper objectMapper) {
        super(requestSender, objectMapper);
    }

    @Override
    String endpointPath() {
        return "/api/v1/indexes/cellml";
    }

    @Override
    Map<String, Object> additionalRequestFields() {
        return Map.of("externalCmeta", externalCmeta.toLowerCase());
    }
}
