package org.masymos.core.spi;

import java.util.Map;

/**
 * One ontology term as the graph stores it: its id and its string properties.
 *
 * @param id the term id, e.g. {@code SBO:0000001} or {@code owl:SBOOntology}
 * @param properties the term's string properties; never null, and copied, so a later write to the
 *     caller's map cannot change a result that has already been handed out
 */
public record OntologyTerm(String id, Map<String, String> properties) {
    public OntologyTerm {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
