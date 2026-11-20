package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.masymos.core.spi.OntologyTerm;

@Schema(description = "One ontology term: its id and the string properties the index wrote for it.")
public record OntologyTermResponse(
        @Schema(description = "The ontology label the term belongs to.", example = "SBOOntology")
        String ontology,
        @Schema(description = "The term id as the graph stores it; may contain a colon.", example = "SBO:0000001")
        String id,
        @Schema(description = "The term's string properties.", example = "{\"name\": \"entity\", \"termType\": \"class\"}")
        Map<String, String> properties
) {
    public OntologyTermResponse {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static OntologyTermResponse from(String ontology, OntologyTerm term) {
        return new OntologyTermResponse(ontology, term.id(), term.properties());
    }
}
