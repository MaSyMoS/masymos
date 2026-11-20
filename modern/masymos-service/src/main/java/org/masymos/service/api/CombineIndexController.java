package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.masymos.core.archive.CombineArchiveIndexingService;
import org.masymos.service.api.dto.CombineIndexResponse;
import org.masymos.service.api.dto.IndexModelsRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/indexes/combine")
@Tag(name = "Indexing COMBINE archives",
        description = "Indexes the model and simulation description entries of COMBINE archives (OMEX)")
public class CombineIndexController {
    private final CombineArchiveIndexingService indexingService;

    public CombineIndexController(CombineArchiveIndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Index COMBINE archives",
            description = "Reads each COMBINE archive (OMEX) source URI, extracts its SBML, CellML and SED-ML "
                    + "entries into a temporary workspace, indexes them into the graph store and writes or "
                    + "updates the corresponding nodes and relationships. Entries that cannot be indexed, such "
                    + "as metadata or images, are not fatal and are reported as skipped. "
                    + "Returns one entry per archive."
    )
    @ApiResponse(responseCode = "201", description = "Archives indexed; the response lists the indexed models per archive")
    @ApiResponse(responseCode = "400", description = "Request body is invalid, e.g. an empty source list or a malformed URI")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public List<CombineIndexResponse> index(@Valid @RequestBody IndexModelsRequest request) {
        return request.sources().stream()
                .map(sourceUri -> CombineIndexResponse.from(sourceUri, indexingService.index(sourceUri)))
                .toList();
    }
}
