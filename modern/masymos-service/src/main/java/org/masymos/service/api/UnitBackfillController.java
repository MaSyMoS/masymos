package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.masymos.core.spi.UnitBackfill;
import org.masymos.service.api.dto.UnitBackfillReportResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The explicit unit backfill (STO-6).
 *
 * <p>It exists because documents indexed before CML-1 carry no unit layer, and the layer can be
 * rebuilt from the source document the graph already stores ({@code xmlDoc}, STO-2). Re-indexing was
 * rejected (decision 36): it appends a version to the chain and needs the original source. The run
 * therefore writes <em>only</em> the unit layer ({@code UNIT}, {@code COMPOSED_OF}, {@code USES_UNIT},
 * {@code HAS_UNIT}) and nothing else (decision 35).
 *
 * <p>{@code apply} defaults to {@code false}, so the default call is a dry run that reports what would
 * be written; a caller asks for writes explicitly. The report separates the skip reasons, so "nothing
 * left to do" ({@code complete}, {@code writes} {@code 0} on a rerun) is distinguishable from "could
 * not look".
 *
 * <p>{@code limit} bounds one request (task T-018, finding GAP02-007): at most {@code limit} documents
 * with a stored source are looked at, the rest are reported as {@code documentsNotScanned}, and {@code
 * 0} means unbounded for a caller that really means it. Since {@code C04-2} a bounded run is also
 * <em>resumable</em>: the report's {@code nextCursor} is the position to pass back as {@code cursor},
 * and the next run continues after it. The cursor is a keyset on the document {@code uid} - the one
 * property the graph constrains to be unique - so it is a position in the population rather than a
 * count of finished work, and a whole corpus is swept in bounded requests instead of one request that
 * holds its thread for as long as the corpus takes.
 */
@RestController
@RequestMapping("/api/v1/maintenance/units-backfill")
@Tag(name = "Maintenance", description = "Maintenance operations on already indexed data")
public class UnitBackfillController {
    private final UnitBackfill unitBackfill;

    public UnitBackfillController(UnitBackfill unitBackfill) {
        this.unitBackfill = unitBackfill;
    }

    @PostMapping
    @Operation(
            summary = "Backfill the unit layer of already indexed documents",
            description = "Brings documents indexed before CML-1 up to the current unit layer, "
                    + "rebuilding it from the source document the graph already stores. "
                    + "This is a dry run unless apply=true: without it nothing is written and the report "
                    + "says what would be. The run writes only the unit layer (UNIT, COMPOSED_OF, "
                    + "USES_UNIT, HAS_UNIT) and never re-indexes, creates a version, or touches the "
                    + "document, model, entity or annotation layer (decisions 35 and 36, task STO-6).")
    @Parameter(
            name = "apply",
            description = "false (default) reports what would be written; true performs the writes.",
            example = "false")
    @Parameter(
            name = "limit",
            description = "Maximum documents with a stored source this run looks at; 0 means unbounded. "
                    + "The rest are reported as documentsNotScanned.",
            example = "500")
    @Parameter(
            name = "cursor",
            description = "Start after this position - the nextCursor of a previous run. Omit to start at "
                    + "the beginning of the population.",
            example = "3f2c8a6e-1b4d-4c31-9a02-5c7d1e8f4b60")
    @ApiResponse(
            responseCode = "200",
            description = "Backfill finished; the counters and the derived writes/complete are returned. "
                    + "A dry run reports writes=0, and nextCursor names the position to continue from.",
            content = @Content(schema = @Schema(implementation = UnitBackfillReportResponse.class)))
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public UnitBackfillReportResponse backfill(
            @RequestParam(name = "apply", defaultValue = "false") boolean apply,
            @RequestParam(name = "limit", defaultValue = "" + UnitBackfill.DEFAULT_LIMIT) int limit,
            @RequestParam(name = "cursor", required = false) String cursor
    ) {
        return UnitBackfillReportResponse.from(unitBackfill.run(apply, limit, cursor));
    }
}
