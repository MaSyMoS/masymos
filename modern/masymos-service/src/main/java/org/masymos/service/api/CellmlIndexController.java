package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.masymos.core.extract.CellmlExternalCmetaResolver;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.service.api.dto.CellmlIndexModelsRequest;
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
@RequestMapping("/api/v1/indexes/cellml")
@Tag(name = "Indexing CellML", description = "Indexes CellML model files, optionally enriched with external cmeta data")
public class CellmlIndexController {
    private final CellmlModelIndexingService indexingService;
    private final CellmlExternalCmetaResolver pmrExternalCmetaResolver;
    private final IndexBoundsProperties indexBounds;

    public CellmlIndexController(
            CellmlModelIndexingService indexingService,
            CellmlExternalCmetaResolver pmrExternalCmetaResolver,
            IndexBoundsProperties indexBounds
    ) {
        this.indexingService = indexingService;
        this.pmrExternalCmetaResolver = pmrExternalCmetaResolver;
        this.indexBounds = indexBounds;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Index CellML models",
            description = "Reads each CellML source URI, extracts the model structure and writes or updates "
                    + "the corresponding model nodes and relationships in the graph store. "
                    + "The externalCmeta option controls whether RDF annotations are additionally "
                    + "resolved via Physiome Model Repository (pmr) or not (none, the default). "
                    + "Returns one entry per indexed model."
    )
    @ApiResponse(responseCode = "201", description = "Models indexed; the response lists the indexed model identifiers")
    @ApiResponse(responseCode = "400", description = "Request body is invalid, e.g. an empty source list, "
            + "a malformed URI or an externalCmeta value other than none/pmr")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    @ApiResponse(responseCode = "502", description = "At least one source could not be indexed; the problem "
            + "detail's outcomes array names what every source did, including the models already written")
    public List<IndexedModelResponse> index(@Valid @RequestBody CellmlIndexModelsRequest request) {
        request.requireWithin(indexBounds);
        var resolver = externalCmetaResolver(request.externalCmetaOrDefault());
        var indexed = IndexRun.runModels(
                request.sources(),
                uri -> indexingService.indexUris(List.of(uri), resolver));
        return indexed.stream()
                .map(IndexedModelResponse::from)
                .toList();
    }

    /**
     * An unknown {@code externalCmeta} value is refused as an {@link IllegalArgumentException}, which
     * {@link ApiExceptionHandler} renders as the documented {@code 400 invalid-argument} (task T-017,
     * finding GAP02-004). It used to be a {@code ResponseStatusException}: the status was right, the
     * media shape was not - the advice declared no branch for it and the property that would have made
     * the framework render one was unset.
     */
    private CellmlExternalCmetaResolver externalCmetaResolver(String externalCmeta) {
        return switch (externalCmeta.toLowerCase()) {
            case "none" -> CellmlExternalCmetaResolver.NONE;
            case "pmr" -> pmrExternalCmetaResolver;
            default -> throw new IllegalArgumentException("externalCmeta must be one of: none, pmr");
        };
    }
}
