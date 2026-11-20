package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.masymos.core.extract.CellmlExternalCmetaResolver;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.service.api.dto.CellmlIndexModelsRequest;
import org.masymos.service.api.dto.IndexedModelResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/indexes/cellml")
@Tag(name = "Indexing CellML", description = "Indexes CellML model files, optionally enriched with external cmeta data")
public class CellmlIndexController {
    private final CellmlModelIndexingService indexingService;
    private final CellmlExternalCmetaResolver pmrExternalCmetaResolver;

    public CellmlIndexController(
            CellmlModelIndexingService indexingService,
            CellmlExternalCmetaResolver pmrExternalCmetaResolver
    ) {
        this.indexingService = indexingService;
        this.pmrExternalCmetaResolver = pmrExternalCmetaResolver;
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
    public List<IndexedModelResponse> index(@Valid @RequestBody CellmlIndexModelsRequest request) throws IOException {
        return indexingService.indexUris(request.sources(), externalCmetaResolver(request.externalCmetaOrDefault()))
                .stream()
                .map(IndexedModelResponse::from)
                .toList();
    }

    private CellmlExternalCmetaResolver externalCmetaResolver(String externalCmeta) {
        return switch (externalCmeta.toLowerCase()) {
            case "none" -> CellmlExternalCmetaResolver.NONE;
            case "pmr" -> pmrExternalCmetaResolver;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "externalCmeta must be one of: none, pmr"
            );
        };
    }
}
