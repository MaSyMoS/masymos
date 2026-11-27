package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.masymos.core.spi.ArchiveQueryRepository;
import org.masymos.core.spi.SedmlQueryRepository;
import org.masymos.service.api.dto.ArchiveSearchResultResponse;
import org.masymos.service.api.dto.QueryModelsRequest;
import org.masymos.service.api.dto.QueryPageResponse;
import org.masymos.service.api.dto.SedmlSearchResultResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/queries")
@Tag(name = "Query", description = "Search endpoints for models and their resources.")
public class TypedQueryController {
    private final SedmlQueryRepository sedmlQueryRepository;
    private final ArchiveQueryRepository archiveQueryRepository;

    public TypedQueryController(
            SedmlQueryRepository sedmlQueryRepository, ArchiveQueryRepository archiveQueryRepository) {
        this.sedmlQueryRepository = sedmlQueryRepository;
        this.archiveQueryRepository = archiveQueryRepository;
    }

    @PostMapping("/sedml")
    @Operation(
            summary = "Search SED-ML descriptions",
            description = "Returns one page of the SED-ML descriptions matching the given query field filters, plus"
                    + " the cursor to continue from. Each hit carries the model sources it references: the resolved"
                    + " source when it could be resolved, otherwise the declared one, so an unresolvable"
                    + " urn:miriam: reference is still reported. An optional minScore floor is applied to the"
                    + " relevance score. groupByVersion and modelTypes are ignored here: this endpoint searches"
                    + " SED-ML descriptions only, so a modelTypes value it cannot apply is ignored rather than"
                    + " rejected - POST /api/v1/queries/models is the endpoint that answers 400 for one. Send a"
                    + " page's nextCursor back as cursor to read on; a cursor belongs to the request that produced"
                    + " it and is refused (400) for any other.")
    @ApiResponse(responseCode = "200", description = "One page of matching SED-ML descriptions returned.")
    @ApiResponse(
            responseCode = "400",
            description = "Request payload failed validation, or the cursor does not belong to it.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public QueryPageResponse<SedmlSearchResultResponse> querySedml(
            @Valid @RequestBody QueryModelsRequest request,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return QueryPageResponse.from(
                sedmlQueryRepository.querySedmlPage(request.toUnfilteredModelQueryRequest().withCursor(cursor)),
                SedmlSearchResultResponse::from);
    }

    @PostMapping("/archives")
    @Operation(
            summary = "Search COMBINE archives",
            description = "Returns one page of the COMBINE archives matching the given query field filters, plus the"
                    + " cursor to continue from. Each hit carries the number of documents it bundles. Use the"
                    + " ARCHIVE_NAME and ARCHIVE_URI fields to match the archive file name and source URI."
                    + " groupByVersion and modelTypes are ignored here: this endpoint searches archives only, so a"
                    + " modelTypes value it cannot apply is ignored rather than rejected - POST"
                    + " /api/v1/queries/models is the endpoint that answers 400 for one. Send a page's nextCursor"
                    + " back as cursor to read on; a cursor belongs to the request that produced it and is refused"
                    + " (400) for any other.")
    @ApiResponse(responseCode = "200", description = "One page of matching COMBINE archives returned.")
    @ApiResponse(
            responseCode = "400",
            description = "Request payload failed validation, or the cursor does not belong to it.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public QueryPageResponse<ArchiveSearchResultResponse> queryArchives(
            @Valid @RequestBody QueryModelsRequest request,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return QueryPageResponse.from(
                archiveQueryRepository.queryArchivesPage(request.toUnfilteredModelQueryRequest().withCursor(cursor)),
                ArchiveSearchResultResponse::from);
    }
}
