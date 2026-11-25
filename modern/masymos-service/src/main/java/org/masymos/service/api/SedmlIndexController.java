package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.masymos.core.extract.SedmlModelIndexingService;
import org.masymos.core.spi.SedmlLinkingRepository;
import org.masymos.service.api.dto.IndexModelsRequest;
import org.masymos.service.api.dto.SedmlIndexResponse;
import org.masymos.service.config.IndexBoundsProperties;
import org.masymos.service.config.SedmlLinkProperties;
import org.masymos.service.indexing.IndexRun;
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
    private final SedmlModelIndexingService indexingService;
    private final SedmlLinkingRepository linkingRepository;
    private final SedmlLinkProperties linkProperties;
    private final IndexBoundsProperties indexBounds;

    public SedmlIndexController(SedmlModelIndexingService indexingService, SedmlLinkingRepository linkingRepository) {
        this(indexingService, linkingRepository, SedmlLinkProperties.defaults(), IndexBoundsProperties.defaults());
    }

    @Autowired
    public SedmlIndexController(
            SedmlModelIndexingService indexingService,
            SedmlLinkingRepository linkingRepository,
            SedmlLinkProperties linkProperties,
            IndexBoundsProperties indexBounds
    ) {
        this.indexingService = indexingService;
        this.linkingRepository = linkingRepository;
        this.linkProperties = linkProperties;
        this.indexBounds = indexBounds;
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
    @ApiResponse(responseCode = "502", description = "At least one source could not be indexed; the problem "
            + "detail's outcomes array names what every source did, including the documents already written")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public List<SedmlIndexResponse> index(@Valid @RequestBody IndexModelsRequest request) {
        request.requireWithin(indexBounds);
        var indexedModels = IndexRun.runModels(
                request.sources(),
                uri -> indexingService.indexUris(List.of(uri)));

        // Task T-017, finding GAP02-005: an outage during the link step is not a skipped feature. It
        // propagates as the documented 503 storage-unavailable, so an operator can tell "the store went
        // away" from "auto-link is off" - the documents indexed before the outage stay written either
        // way, and the response no longer claims a complete run.
        var autoLink = linkProperties.autoLink();
        var links = autoLink ? linkingRepository.linkSedmlReferences() : null;
        var status = autoLink
                ? SedmlIndexResponse.SedmlLinkStatus.COMPLETED
                : SedmlIndexResponse.SedmlLinkStatus.SKIPPED;

        var linkResult = links;
        var linkStatus = status;
        return indexedModels.stream()
                .map(model -> SedmlIndexResponse.from(model, linkStatus, linkResult))
                .toList();
    }
}
