package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.masymos.core.extract.SedmlModelIndexingService;
import org.masymos.core.model.SedmlLinkingResult;
import org.masymos.core.spi.SedmlLinkingRepository;
import org.masymos.service.api.dto.IndexModelsRequest;
import org.masymos.service.api.dto.SedmlIndexResponse;
import org.masymos.service.config.SedmlLinkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/indexes/sedml")
@Tag(name = "Indexing SED-ML", description = "Indexes SED-ML simulation description files into the graph store")
public class SedmlIndexController {
    private static final Logger LOGGER = LoggerFactory.getLogger(SedmlIndexController.class);

    private final SedmlModelIndexingService indexingService;
    private final SedmlLinkingRepository linkingRepository;
    private final SedmlLinkProperties linkProperties;

    public SedmlIndexController(SedmlModelIndexingService indexingService, SedmlLinkingRepository linkingRepository) {
        this(indexingService, linkingRepository, SedmlLinkProperties.defaults());
    }

    @Autowired
    public SedmlIndexController(
            SedmlModelIndexingService indexingService,
            SedmlLinkingRepository linkingRepository,
            SedmlLinkProperties linkProperties
    ) {
        this.indexingService = indexingService;
        this.linkingRepository = linkingRepository;
        this.linkProperties = linkProperties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Index SED-ML documents",
            description = "Reads each SED-ML source URI, extracts the simulation description "
                    + "(models, simulations, tasks, data generators, outputs) and writes or updates "
                    + "the corresponding nodes and relationships in the graph store. "
                    + "When automatic linking is enabled, the SED-ML cross-document references are "
                    + "resolved once afterwards and the resulting counters are reported. "
                    + "Returns one entry per indexed document."
    )
    @ApiResponse(responseCode = "201", description = "Documents indexed; the response lists the indexed model identifiers")
    @ApiResponse(responseCode = "400", description = "Request body is invalid, e.g. an empty source list or a malformed URI")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public List<SedmlIndexResponse> index(@Valid @RequestBody IndexModelsRequest request) throws IOException {
        var indexedModels = indexingService.indexUris(request.sources());

        var status = SedmlIndexResponse.SedmlLinkStatus.SKIPPED;
        SedmlLinkingResult links = null;
        if (linkProperties.autoLink()) {
            try {
                links = linkingRepository.linkSedmlReferences();
                status = SedmlIndexResponse.SedmlLinkStatus.COMPLETED;
            } catch (StorageUnavailableException unavailable) {
                LOGGER.warn("SED-ML documents were indexed, but automatic linking is unavailable: {}",
                        unavailable.getMessage());
            }
        }

        var linkResult = links;
        var linkStatus = status;
        return indexedModels.stream()
                .map(model -> SedmlIndexResponse.from(model, linkStatus, linkResult))
                .toList();
    }
}
