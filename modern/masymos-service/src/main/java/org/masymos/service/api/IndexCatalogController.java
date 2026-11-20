package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.masymos.core.model.IndexSummary;
import org.masymos.core.spi.IndexCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists the indexes the store keeps. Read-only and separate from {@code /api/v1/indexes/{kind}}, which
 * starts an indexing run — same collection path, different method and meaning.
 */
@RestController
@Tag(name = "Indexes", description = "The indexes the model store keeps.")
public class IndexCatalogController {
    private final IndexCatalog indexCatalog;

    public IndexCatalogController(IndexCatalog indexCatalog) {
        this.indexCatalog = indexCatalog;
    }

    @Operation(
            summary = "List indexes",
            description = "Returns the store's own index catalogue: name, kind, entity type, labels or types,"
                    + " properties and state, ordered by name. Nothing is derived, so an index that failed to"
                    + " create is absent and one still populating says so. Reports 503 when storage is disabled"
                    + " or unreachable.")
    @ApiResponse(responseCode = "200", description = "Index catalogue returned.")
    @ApiResponse(responseCode = "503", description = "The store could not be asked.")
    @GetMapping("/api/v1/indexes")
    public List<IndexSummary> indexes() {
        return indexCatalog.list();
    }
}
