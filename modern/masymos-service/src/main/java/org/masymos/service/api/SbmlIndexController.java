package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.masymos.core.extract.SbmlModelIndexingService;
import org.masymos.service.api.dto.IndexModelsRequest;
import org.masymos.service.api.dto.IndexedModelResponse;
import org.masymos.service.config.IndexBoundsProperties;
import org.masymos.service.indexing.IndexRun;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/indexes/sbml")
@Tag(name = "Indexing SBML", description = "Indexes SBML model files into the graph store")
public class SbmlIndexController {
    private final SbmlModelIndexingService indexingService;
    private final IndexBoundsProperties indexBounds;

    public SbmlIndexController(SbmlModelIndexingService indexingService, IndexBoundsProperties indexBounds) {
        this.indexingService = indexingService;
        this.indexBounds = indexBounds;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Index SBML models",
            description = "Reads each SBML source URI, extracts the model structure and writes or updates "
                    + "the corresponding model nodes and their relationships in the graph store. "
                    + "Returns one entry per indexed model."
    )
    @ApiResponse(responseCode = "201", description = "Models indexed; the response lists the indexed model identifiers")
    @ApiResponse(responseCode = "400", description = "Request body is invalid, e.g. an empty source list or a malformed URI")
    @ApiResponse(responseCode = "502", description = "At least one source could not be indexed; the problem "
            + "detail's outcomes array names what every source did, including the models already written")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public List<IndexedModelResponse> index(@Valid @RequestBody IndexModelsRequest request) {
        request.requireWithin(indexBounds);
        var indexed = IndexRun.runModels(
                request.sources(),
                uri -> indexingService.indexUris(List.of(uri)));
        return indexed.stream()
                .map(IndexedModelResponse::from)
                .toList();
    }
}
