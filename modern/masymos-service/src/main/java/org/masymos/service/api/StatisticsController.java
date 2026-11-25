package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.masymos.core.model.StoreStatistics;
import org.masymos.core.spi.StatisticsRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Counts of what the store holds; the graph half of the legacy statistics, as one read. */
@RestController
@Tag(name = "Statistics", description = "Counts of the stored graph.")
public class StatisticsController {
    private final StatisticsRepository statisticsRepository;

    public StatisticsController(StatisticsRepository statisticsRepository) {
        this.statisticsRepository = statisticsRepository;
    }

    @Operation(
            summary = "Store statistics",
            description = "Documents (versions), models, models per kind, persons, publications, annotations"
                    + " and archives, counted in one read. The per-kind counts use the same labels the query"
                    + " surface filters on. Reports 503 when storage is disabled or unreachable.")
    @ApiResponse(responseCode = "200", description = "Counts returned.")
    @ApiResponse(responseCode = "503", description = "The store could not be asked.")
    @GetMapping("/api/v1/stats")
    public StoreStatistics statistics() {
        return statisticsRepository.statistics();
    }
}
