package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.masymos.core.query.fusion.RankFusionQueryService;
import org.masymos.service.api.dto.AggregatedHitResponse;
import org.masymos.service.api.dto.AggregatedQueryRequest;
import org.masymos.service.api.dto.FusionStrategyResponse;
import org.masymos.service.api.dto.QueryPageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/queries")
@Tag(
        name = "Query",
        description = "Search endpoints for models and their resources, including the rank-fused aggregated query.")
public class AggregatedQueryController {
    private final RankFusionQueryService fusionService;

    public AggregatedQueryController(RankFusionQueryService fusionService) {
        this.fusionService = fusionService;
    }

    @PostMapping("/aggregated")
    @Operation(
            summary = "Run an aggregated, rank-fused model query",
            description = "Runs the model, annotation, person and publication queries for the given keyword and"
                    + " clauses, fuses their rankings with the requested strategy and returns one page of rows,"
                    + " one per model file, plus the cursor to continue from. top counts files, not versions."
                    + " Send a page's nextCursor back as cursor to read on; a cursor belongs to the request that"
                    + " produced it and is refused (400) for any other, and so is one whose result set changed"
                    + " under the sweep. fusedScore is on the strategy's own scale only:"
                    + " 0 < fusedScore <= 1 for bordaCombMnz, null for the order-only bestRank, and it is not"
                    + " comparable to per-entity scores, to legacy scores or across strategies. Clauses on fields"
                    + " other than KEYWORD reach the model ranker only. weights affect bordaCombMnz only.")
    @ApiResponse(responseCode = "200", description = "One page of fused model rows returned, one per model file.")
    @ApiResponse(
            responseCode = "400",
            description = "Request payload failed validation, the strategy is unknown, or the cursor does not"
                    + " belong to the request.")
    @ApiResponse(responseCode = "503", description = "Model storage is disabled.")
    public QueryPageResponse<AggregatedHitResponse> queryAggregated(
            @Valid @RequestBody AggregatedQueryRequest request,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return QueryPageResponse.from(
                fusionService.queryPage(request.toAggregatedQuery().withCursor(cursor)),
                AggregatedHitResponse::from);
    }

    @GetMapping("/strategies")
    @Operation(
            summary = "List fusion strategies",
            description = "Returns the available rank-fusion strategies sorted by id, with whether each produces a"
                    + " fusedScore. For a strategy with producesScore false the fusedScore of an aggregated hit is"
                    + " null and only the order is defined.")
    @ApiResponse(responseCode = "200", description = "Fusion strategies returned.")
    public List<FusionStrategyResponse> strategies() {
        return fusionService.strategies().stream()
                .map(strategy ->
                        new FusionStrategyResponse(strategy.id(), strategy.producesScore(), strategy.description()))
                .toList();
    }
}
