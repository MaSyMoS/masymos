package org.masymos.core.extract;

import java.util.List;
import org.masymos.core.model.ModelReference;

/**
 * The graph a parser produced for one document, plus the source document itself when the caller
 * captured it (STO-2). {@code sourceDocument} is {@code null} for callers that build a graph
 * without reading a document, e.g. a test fixture.
 */
public record ExtractedModelGraph(
        ModelReference reference,
        List<ExtractedGraphNode> nodes,
        List<ExtractedGraphRelationship> relationships,
        SourceDocument sourceDocument
) {
    public ExtractedModelGraph(
            ModelReference reference,
            List<ExtractedGraphNode> nodes,
            List<ExtractedGraphRelationship> relationships
    ) {
        this(reference, nodes, relationships, null);
    }

    /** Attaches the captured document without rebuilding nodes or relationships. */
    public ExtractedModelGraph withSourceDocument(SourceDocument document) {
        return new ExtractedModelGraph(reference, nodes, relationships, document);
    }
}
