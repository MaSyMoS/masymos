package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.ResourceQueryRepository;
import org.masymos.service.api.dto.AnnotationSearchResultResponse;
import org.masymos.service.api.dto.ModelSearchResultResponse;
import org.masymos.service.api.dto.PersonSearchResultResponse;
import org.masymos.service.api.dto.PublicationSearchResultResponse;
import org.masymos.service.api.dto.QueryModelsRequest;
import org.masymos.service.api.dto.QueryPageResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
            description = "Returns one page of the models matching the given query field filters, plus the cursor"
                    + " to continue from. The optional minScore floor is ignored by model queries. With"
                    + " groupByVersion each row carries the matching versions of one model file in versions, and"
                    + " top counts groups instead of single hits. Send a page's nextCursor back as cursor to read"
                    + " on; a cursor belongs to the request that produced it and is refused (400) for any other.")
    @ApiResponse(responseCode = "200", description = "One page of matching models returned.")
    @ApiResponse(
            responseCode = "400",
            description = "Request payload failed validation, or the cursor does not belong to it.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public QueryPageResponse<ModelSearchResultResponse> queryModels(
            @Valid @RequestBody QueryModelsRequest request,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return QueryPageResponse.from(
                modelRepository.queryModelsPage(request.toModelQueryRequest().withCursor(cursor)),
                ModelSearchResultResponse::from);
    }

    @PostMapping("/annotations")
    @Operation(
            summary = "Search annotations",
            description = "Returns one page of the annotations matching the given query field filters, plus the"
                    + " cursor to continue from. An optional minScore floor is applied to the relevance score."
                    + " groupByVersion is ignored here. Send a page's nextCursor back as cursor to read on; a"
                    + " cursor belongs to the request that produced it and is refused (400) for any other.")
    @ApiResponse(responseCode = "200", description = "One page of matching annotations returned.")
    @ApiResponse(
            responseCode = "400",
            description = "Request payload failed validation, or the cursor does not belong to it.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public QueryPageResponse<AnnotationSearchResultResponse> queryAnnotations(
            @Valid @RequestBody QueryModelsRequest request,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return QueryPageResponse.from(
                resourceQueryRepository.queryAnnotationsPage(request.toModelQueryRequest().withCursor(cursor)),
                AnnotationSearchResultResponse::from);
    }

    @PostMapping("/persons")
    @Operation(
            summary = "Search persons",
            description = "Returns one page of the persons matching the given query field filters, plus the cursor"
                    + " to continue from. An optional minScore floor is applied to the relevance score."
                    + " groupByVersion is ignored here. Send a page's nextCursor back as cursor to read on; a"
                    + " cursor belongs to the request that produced it and is refused (400) for any other.")
    @ApiResponse(responseCode = "200", description = "One page of matching persons returned.")
    @ApiResponse(
            responseCode = "400",
            description = "Request payload failed validation, or the cursor does not belong to it.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public QueryPageResponse<PersonSearchResultResponse> queryPersons(
            @Valid @RequestBody QueryModelsRequest request,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return QueryPageResponse.from(
                resourceQueryRepository.queryPersonsPage(request.toModelQueryRequest().withCursor(cursor)),
                PersonSearchResultResponse::from);
    }

    @PostMapping("/publications")
    @Operation(
            summary = "Search publications",
            description = "Returns one page of the publications matching the given query field filters, plus the"
                    + " cursor to continue from. An optional minScore floor is applied to the relevance score."
                    + " groupByVersion is ignored here. Send a page's nextCursor back as cursor to read on; a"
                    + " cursor belongs to the request that produced it and is refused (400) for any other.")
    @ApiResponse(responseCode = "200", description = "One page of matching publications returned.")
    @ApiResponse(
            responseCode = "400",
            description = "Request payload failed validation, or the cursor does not belong to it.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public QueryPageResponse<PublicationSearchResultResponse> queryPublications(
            @Valid @RequestBody QueryModelsRequest request,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return QueryPageResponse.from(
                resourceQueryRepository.queryPublicationsPage(request.toModelQueryRequest().withCursor(cursor)),
                PublicationSearchResultResponse::from);
    }
}
