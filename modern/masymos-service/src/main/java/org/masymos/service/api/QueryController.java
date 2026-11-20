package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.ResourceQueryRepository;
import org.masymos.service.api.dto.AnnotationSearchResultResponse;
import org.masymos.service.api.dto.ModelSearchResultResponse;
import org.masymos.service.api.dto.PersonSearchResultResponse;
import org.masymos.service.api.dto.PublicationSearchResultResponse;
import org.masymos.service.api.dto.QueryModelsRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/queries")
@Tag(name = "Query", description = "Search endpoints for models and their resources.")
public class QueryController {
    private final ModelRepository modelRepository;
    private final ResourceQueryRepository resourceQueryRepository;

    public QueryController(ModelRepository modelRepository, ResourceQueryRepository resourceQueryRepository) {
        this.modelRepository = modelRepository;
        this.resourceQueryRepository = resourceQueryRepository;
    }

    @PostMapping("/models")
    @Operation(
            summary = "Search models",
            description = "Returns all models matching the given query field filters. The optional minScore floor"
                    + " is ignored by model queries. With groupByVersion each row carries the matching versions"
                    + " of one model file in versions, and top counts groups instead of single hits.")
    @ApiResponse(responseCode = "200", description = "Matching models returned.")
    @ApiResponse(responseCode = "400", description = "Request payload failed validation.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public List<ModelSearchResultResponse> queryModels(@Valid @RequestBody QueryModelsRequest request) {
        return modelRepository.queryModels(request.toModelQueryRequest())
                .stream()
                .map(ModelSearchResultResponse::from)
                .toList();
    }

    @PostMapping("/annotations")
    @Operation(
            summary = "Search annotations",
            description = "Returns all annotations matching the given query field filters. An optional minScore"
                    + " floor is applied to the relevance score. groupByVersion is ignored here.")
    @ApiResponse(responseCode = "200", description = "Matching annotations returned.")
    @ApiResponse(responseCode = "400", description = "Request payload failed validation.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public List<AnnotationSearchResultResponse> queryAnnotations(@Valid @RequestBody QueryModelsRequest request) {
        return resourceQueryRepository.queryAnnotations(request.toModelQueryRequest())
                .stream()
                .map(AnnotationSearchResultResponse::from)
                .toList();
    }

    @PostMapping("/persons")
    @Operation(
            summary = "Search persons",
            description = "Returns all persons matching the given query field filters. An optional minScore"
                    + " floor is applied to the relevance score. groupByVersion is ignored here.")
    @ApiResponse(responseCode = "200", description = "Matching persons returned.")
    @ApiResponse(responseCode = "400", description = "Request payload failed validation.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public List<PersonSearchResultResponse> queryPersons(@Valid @RequestBody QueryModelsRequest request) {
        return resourceQueryRepository.queryPersons(request.toModelQueryRequest())
                .stream()
                .map(PersonSearchResultResponse::from)
                .toList();
    }

    @PostMapping("/publications")
    @Operation(
            summary = "Search publications",
            description = "Returns all publications matching the given query field filters. An optional minScore"
                    + " floor is applied to the relevance score. groupByVersion is ignored here.")
    @ApiResponse(responseCode = "200", description = "Matching publications returned.")
    @ApiResponse(responseCode = "400", description = "Request payload failed validation.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public List<PublicationSearchResultResponse> queryPublications(@Valid @RequestBody QueryModelsRequest request) {
        return resourceQueryRepository.queryPublications(request.toModelQueryRequest())
                .stream()
                .map(PublicationSearchResultResponse::from)
                .toList();
    }
}
