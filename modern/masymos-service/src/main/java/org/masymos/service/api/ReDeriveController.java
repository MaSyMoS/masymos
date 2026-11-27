package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.masymos.core.rederive.ReDeriveReport;
import org.masymos.core.spi.ReDerive;
import org.masymos.service.api.dto.ReDeriveReportResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The re-derive scan ({@code OPN-7}, 2026-11-26): which already indexed documents the current mapper
 * would produce differently, and in what respect.
 *
 * <p>It is read-only on purpose, and it is the first slice of the task, not the whole of it. The write
 * path creates its nodes instead of merging them, so a repair needs an identity to merge on; since
 * 2026-11-26 the write path stamps {@code derivedKey} on every node it owns, but every document indexed
 * before that lacks it. This endpoint measures the population before anything writes to it: a
 * <em>shortfall</em> says the write path gained a construct, and {@code missingDerivedKeys} says the
 * document predates the property a repair keys on.
 *
 * <p>The bound and the cursor are the {@code C04-2} contract the unit backfill uses: {@code limit}
 * bounds one request, {@code 0} means unbounded, and {@code nextCursor} is the position to pass back as
 * {@code cursor}.
 *
 * <p>Since the second slice of {@code OPN-7} the same sweep can <em>repair</em>: with {@code apply=true}
 * it reaches {@link ReDerive#apply(int, String)} instead of {@link ReDerive#run(int, String)}, and the
 * report's {@code applied} names how many documents it rewrote. Without the flag the endpoint stays the
 * read-only scan it was.
 */
@RestController
@RequestMapping("/api/v1/maintenance/re-derive")
@Tag(name = "Maintenance", description = "Maintenance operations on already indexed data")
public class ReDeriveController {
    private final ReDerive reDerive;

    public ReDeriveController(ReDerive reDerive) {
        this.reDerive = reDerive;
    }

    @PostMapping
    @Operation(
            summary = "Report which indexed documents the current mapper would produce differently, "
                    + "and with apply=true repair them",
            description = "Re-parses each stored source document and compares what the current mapper "
                    + "yields with what the graph holds. By default it is read-only: nothing is written, "
                    + "and the report names the node shortfall per label set and how many owned nodes "
                    + "carry no derivedKey. With apply=true it rewrites: each stale document's derived "
                    + "graph is re-derived from its stored source, in one transaction per document, "
                    + "writing no new version, and a truncated source is skipped with that reason. "
                    + "Bounded and resumable: at most limit documents per request, and nextCursor is the "
                    + "position to continue from.")
    @Parameter(
            name = "limit",
            description = "Maximum documents with a stored source this scan looks at; 0 means unbounded. "
                    + "The rest are reported as documentsNotScanned.",
            example = "500")
    @Parameter(
            name = "cursor",
            description = "Start after this position - the nextCursor of a previous run. Omit to start at "
                    + "the beginning of the population.",
            example = "3f2c8a6e-1b4d-4c31-9a02-5c7d1e8f4b60")
    @Parameter(
            name = "apply",
            description = "True to repair instead of only report: each stale document's derived graph is "
                    + "rewritten from its stored source, in one transaction per document, and the report's "
                    + "applied counts them. Omit or send false for a read-only scan.",
            example = "false")
    @ApiResponse(
            responseCode = "200",
            description = "Scan finished; the counters, the per-label shortfall and the position to continue from are returned.",
            content = @Content(schema = @Schema(implementation = ReDeriveReportResponse.class)))
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public ReDeriveReportResponse scan(
            @RequestParam(name = "limit", defaultValue = "" + ReDeriveReport.DEFAULT_LIMIT) int limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "apply", required = false, defaultValue = "false") boolean apply
    ) {
        return ReDeriveReportResponse.from(apply ? reDerive.apply(limit, cursor) : reDerive.run(limit, cursor));
    }
}
