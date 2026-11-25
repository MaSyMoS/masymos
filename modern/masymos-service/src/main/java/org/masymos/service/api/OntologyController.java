package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.masymos.core.spi.OntologyTraversal;
import org.masymos.service.api.dto.OntologyDepthResponse;
import org.masymos.service.api.dto.OntologySimilarityResponse;
import org.masymos.service.api.dto.OntologyTermResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ontology traversal a legacy caller reached through {@code OntologyUtil}'s public statics.
 *
 * <p>Every answer is computed over the {@code isA} edges the index wrote: the {@code rdfs:subClassOf}
 * edges a document states, plus the named conjunct of an {@code intersectionOf} definition, marked
 * {@code derivedFrom = equivalentClass} ({@code OWL-5}). No reasoner is applied, so a subclass relation
 * that only a DL reasoner would find does not contribute to a depth, an ancestor or a score.
 *
 * <p>The label is validated here before any query runs, and {@code OntologyQueryCypher} repeats the
 * check as the injection guard for the label it interpolates into the Cypher — a label cannot be a
 * parameter. The rule is the one the parser guarantees: every label it derives ends in
 * {@code Ontology}.
 */
@RestController
@RequestMapping("/api/v1/ontologies/{ontology}")
@Tag(
        name = "Ontology traversal",
        description = "Reads the hierarchy an indexed OWL ontology produced: terms, depth, root and similarity")
public class OntologyController {
    private static final int MAX_MAX_DEPTH = 64;
    private static final String ONTOLOGY_LABEL = "[A-Za-z_][A-Za-z0-9_]*Ontology";

    private final OntologyTraversal traversal;

    public OntologyController(OntologyTraversal traversal) {
        this.traversal = traversal;
    }

    @GetMapping("/terms/{id}")
    @Operation(
            summary = "One ontology term",
            description = "Returns the term's id and its string properties. The id is taken as written, so an "
                    + "id containing a colon needs no encoding.")
    @ApiResponse(responseCode = "200", description = "The term")
    @ApiResponse(responseCode = "400", description = "The ontology label is not a legal label")
    @ApiResponse(responseCode = "404", description = "No term with that id is indexed for the ontology")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public OntologyTermResponse term(
            @PathVariable("ontology") String ontology, @PathVariable("id") String id) {
        requireLabel(ontology);
        return OntologyTermResponse.from(
                ontology,
                traversal.term(ontology, id).orElseThrow(() -> notIndexed(ontology, id)));
    }

    @GetMapping("/root")
    @Operation(
            summary = "The ontology's root term",
            description = "The root is the term whose id is owl:<ontology label>; every top-level class has an "
                    + "isA edge to it.")
    @ApiResponse(responseCode = "200", description = "The root term")
    @ApiResponse(responseCode = "400", description = "The ontology label is not a legal label")
    @ApiResponse(responseCode = "404", description = "The ontology is not indexed")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public OntologyTermResponse root(@PathVariable("ontology") String ontology) {
        requireLabel(ontology);
        return OntologyTermResponse.from(
                ontology,
                traversal.root(ontology).orElseThrow(
                        () -> new ResourceNotFoundException("ontology " + ontology + " is not indexed")));
    }

    @GetMapping("/terms/{id}/depth")
    @Operation(
            summary = "Depth of a term",
            description = "The number of isA hops from the term to the ontology root, over the isA edges the"
                    + " parser extracted: stated rdfs:subClassOf plus a definition's named conjunct.")
    @ApiResponse(responseCode = "200", description = "The depth, or a null depth when no path to the root exists")
    @ApiResponse(responseCode = "400", description = "Illegal ontology label, or maxDepth outside 1-64")
    @ApiResponse(responseCode = "404", description = "No term with that id is indexed for the ontology")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public OntologyDepthResponse depth(
            @PathVariable("ontology") String ontology,
            @PathVariable("id") String id,
            @RequestParam(name = "maxDepth", defaultValue = "32") int maxDepth) {
        requireLabel(ontology);
        requireDepth(maxDepth);
        var depth = traversal.depth(ontology, id, maxDepth);
        if (depth.isEmpty() && traversal.term(ontology, id).isEmpty()) {
            throw notIndexed(ontology, id);
        }
        return new OntologyDepthResponse(ontology, id, depth.orElse(null));
    }

    @GetMapping("/similarity")
    @Operation(
            summary = "Similarity of two terms",
            description = "The legacy formula over the shortest isA distance between the terms, their lowest "
                    + "common ancestor and that ancestor's depth, every leg bounded by maxDepth. The four derived "
                    + "fields are null together when no common ancestor was found within that bound.")
    @ApiResponse(responseCode = "200", description = "The similarity, or null fields when no common ancestor is found within maxDepth")
    @ApiResponse(responseCode = "400", description = "Illegal ontology label, blank term id, or maxDepth outside 1-64")
    @ApiResponse(responseCode = "404", description = "One of the terms is not indexed for the ontology")
    @ApiResponse(responseCode = "503", description = "The graph store is not available")
    public OntologySimilarityResponse similarity(
            @PathVariable("ontology") String ontology,
            @RequestParam(name = "first", required = false) String first,
            @RequestParam(name = "second", required = false) String second,
            @RequestParam(name = "maxDepth", defaultValue = "32") int maxDepth) {
        requireLabel(ontology);
        requireDepth(maxDepth);
        requireTermId(first, "first");
        requireTermId(second, "second");
        var similarity = traversal.similarity(ontology, first, second, maxDepth);
        if (similarity.isEmpty()) {
            // The traversal cannot say which side was unknown, so ask; only reached when it produced nothing.
            if (traversal.term(ontology, first).isEmpty()) {
                throw notIndexed(ontology, first);
            }
            if (traversal.term(ontology, second).isEmpty()) {
                throw notIndexed(ontology, second);
            }
        }
        return OntologySimilarityResponse.from(ontology, first, second, similarity.orElse(null));
    }

    private static void requireLabel(String ontology) {
        if (ontology == null || !ontology.matches(ONTOLOGY_LABEL)) {
            throw new IllegalArgumentException("not an ontology label: " + ontology);
        }
    }

    private static void requireDepth(int maxDepth) {
        if (maxDepth < 1 || maxDepth > MAX_MAX_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_MAX_DEPTH + ": " + maxDepth);
        }
    }

    private static void requireTermId(String id, String parameter) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(parameter + " must name a term id");
        }
    }

    private static ResourceNotFoundException notIndexed(String ontology, String id) {
        return new ResourceNotFoundException("term " + id + " is not indexed for ontology " + ontology);
    }
}
