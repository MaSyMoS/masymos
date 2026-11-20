package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.masymos.core.extract.OwlOntologyIndexingService;
import org.masymos.service.api.dto.IndexModelsRequest;
import org.masymos.service.api.dto.IndexedModelResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/indexes/owl")
@Tag(name = "Indexing OWL", description = "Indexes OWL ontologies used for semantic annotation")
public class OwlIndexController {
    private final OwlOntologyIndexingService indexingService;

    public OwlIndexController(OwlOntologyIndexingService indexingService) {
        this.indexingService = indexingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Index OWL ontologies",
            description = "Reads each OWL source URI, extracts classes, properties and their hierarchy "
                    + "and writes or updates the corresponding ontology nodes and relationships "
                    + "in the graph store. Returns one entry per indexed ontology."
    )
    @ApiResponse(responseCode = "201", description = "Ontologies indexed; the response lists the indexed identifiers")
    @ApiResponse(responseCode = "400", description = "Request body is invalid, e.g. an empty source list or a malformed URI")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public List<IndexedModelResponse> index(@Valid @RequestBody IndexModelsRequest request) throws IOException {
        return indexingService.indexUris(request.sources())
                .stream()
                .map(IndexedModelResponse::from)
                .toList();
    }
}
