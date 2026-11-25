package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.masymos.core.archive.CombineArchiveIndexingService;
import org.masymos.core.archive.CombineArchiveIndexingService.CombineArchiveIndexingResult;
import org.masymos.service.api.dto.CombineIndexResponse;
import org.masymos.service.api.dto.IndexModelsRequest;
import org.masymos.service.config.IndexBoundsProperties;
import org.masymos.service.indexing.IndexRun;
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
    private final IndexBoundsProperties indexBounds;

    public CombineIndexController(CombineArchiveIndexingService indexingService, IndexBoundsProperties indexBounds) {
        this.indexingService = indexingService;
        this.indexBounds = indexBounds;
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
    @ApiResponse(responseCode = "502", description = "At least one archive could not be indexed; the problem "
            + "detail's outcomes array names what every source did, including the models already written")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public List<CombineIndexResponse> index(@Valid @RequestBody IndexModelsRequest request) {
        request.requireWithin(indexBounds);
        var archived = IndexRun.run(
                request.sources(),
                uri -> new Archived(uri, indexingService.index(uri)),
                entry -> entry.result().models());
        return archived.stream()
                .map(entry -> CombineIndexResponse.from(entry.sourceUri(), entry.result()))
                .toList();
    }

    /** One source's archive result, so the answer can name the source the run loop supplied. */
    private record Archived(URI sourceUri, CombineArchiveIndexingResult result) {}
}
