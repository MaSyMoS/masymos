package org.masymos.core.extract;

import java.util.List;
import java.util.Map;
import org.masymos.core.model.ModelReference;

public record ParsedOntology(
        ModelReference reference,
        String ontologyName,
        String ontologyLabel,
        Map<String, String> properties,
        List<ParsedOntologyTerm> terms,
        List<ParsedOntologyRelationship> relationships
) {
}
