package org.masymos.core.model;

public record SedmlLinkingResult(
        long modelReferenceLinks,
        long sbmlObservationLinks,
        long cellmlObservationLinks
) {
}
