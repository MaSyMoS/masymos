package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

class ExtractedModelGraphTest {
    @Test
    void allowsAGraphWithoutASourceDocumentForCallersThatBuildGraphsDirectly() {
        var graph = new ExtractedModelGraph(reference(), List.of(), List.of());

        assertNull(graph.sourceDocument());
    }

    @Test
    void attachesTheCapturedSourceDocumentWithoutRebuildingTheGraph() {
        var graph = new ExtractedModelGraph(reference(), List.of(), List.of());
        var document = SourceDocument.from("<sbml/>".getBytes(StandardCharsets.UTF_8), 1024);

        var captured = graph.withSourceDocument(document);

        assertSame(graph.reference(), captured.reference());
        assertEquals(graph.nodes(), captured.nodes());
        assertEquals(graph.relationships(), captured.relationships());
        assertSame(document, captured.sourceDocument());
    }

    private static ModelReference reference() {
        return new ModelReference("demo.sbml", URI.create("file:///demo.sbml"), ModelType.SBML);
    }
}
