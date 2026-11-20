package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description =
                "The isA distance from a term to the ontology root, over the isA edges the parser extracted:"
                        + " stated rdfs:subClassOf plus a definition's named conjunct. A relation that only a"
                        + " DL reasoner would find does not count (no reasoner is applied).")
public record OntologyDepthResponse(
        @Schema(description = "The ontology label.", example = "SBOOntology")
        String ontology,
        @Schema(description = "The term id that was asked about.", example = "SBO:0000001")
        String id,
        @Schema(
                description =
                        "The number of isA hops to the root; null when the term is indexed but no path to the"
                                + " root was found within maxDepth, which is not the same as the term being unknown.",
                example = "2")
        Integer depth
) {
}
