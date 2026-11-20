package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.masymos.core.spi.OntologySimilarity;

@Schema(
        description =
                "The similarity of two ontology terms through their lowest common ancestor, computed over"
                        + " the isA edges the parser extracted: stated rdfs:subClassOf plus a definition's named"
                        + " conjunct. The four derived fields are null together when no common"
                        + " ancestor was found within maxDepth - the terms may share none, or the ancestor may sit"
                        + " deeper than the bound - which is a limit of the request, not a claim that no ancestor"
                        + " exists. An unknown term is a 404 instead.")
public record OntologySimilarityResponse(
        @Schema(description = "The ontology label.", example = "SBOOntology")
        String ontology,
        @Schema(description = "The first term id.", example = "SBO:0000001")
        String first,
        @Schema(description = "The second term id.", example = "SBO:0000002")
        String second,
        @Schema(description = "Shortest undirected isA distance between the terms; null when there is no ancestor.",
                example = "2")
        Integer pathLength,
        @Schema(description = "Id of the lowest term both are descendants of; null when there is none.",
                example = "SBO:0000003")
        String lowestCommonAncestor,
        @Schema(description = "isA distance from that ancestor to the root; null when there is no ancestor.",
                example = "1")
        Integer lowestCommonAncestorDepth,
        @Schema(description = "The legacy formula's score over those three numbers, between 0 and 1.",
                example = "0.36")
        Double similarity
) {
    public static OntologySimilarityResponse from(String ontology, String first, String second,
            OntologySimilarity similarity) {
        if (similarity == null) {
            return new OntologySimilarityResponse(ontology, first, second, null, null, null, null);
        }
        return new OntologySimilarityResponse(
                ontology,
                first,
                second,
                similarity.pathLength(),
                similarity.lowestCommonAncestor(),
                similarity.lowestCommonAncestorDepth(),
                similarity.similarity());
    }
}
