package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.masymos.core.spi.SedmlLinkingRepository;
import org.masymos.service.api.dto.SedmlLinkingResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/maintenance/sedml")
@Tag(name = "Maintenance", description = "Maintenance operations on already indexed data")
public class SedmlMaintenanceController {
    private final SedmlLinkingRepository linkingRepository;

    public SedmlMaintenanceController(SedmlLinkingRepository linkingRepository) {
        this.linkingRepository = linkingRepository;
    }

    @PostMapping("/links")
    @Operation(
            summary = "Relink SED-ML references",
            description = "Walks the already indexed SED-ML relationships and re-resolves their targets, "
                    + "repairing or creating the corresponding links in the graph store. "
                    + "Has no input; reports the number of links handled per relationship type."
    )
    @ApiResponse(responseCode = "200", description = "Relinking finished; counts per relationship type are returned")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public SedmlLinkingResponse linkReferences() {
        return SedmlLinkingResponse.from(linkingRepository.linkSedmlReferences());
    }
}
