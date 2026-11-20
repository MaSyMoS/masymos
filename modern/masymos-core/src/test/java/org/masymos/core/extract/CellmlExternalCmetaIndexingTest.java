package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.spi.ExtractedModelGraphRepository;

/**
 * CML-3: external PMR cmeta is opt-in, a {@code file:} source is readable, and the keywords that were
 * carried but never written now reach the graph as flattened text on the external-cmeta annotation.
 *
 * <p>They stay on that annotation rather than becoming a model search property: the annotation
 * full-text index covers {@code RESOURCE} nodes only, so indexing the keywords for search needs a
 * {@code modelFulltextV5} generation bump, which is a separate, ledgered decision.
 */
class CellmlExternalCmetaIndexingTest {
    private static final Path FIXTURES = Path.of("src", "test", "resources", "cellml-cmeta");

    @BeforeEach
    void createTestDirectory() throws Exception {
        Files.createDirectories(Path.of("target", "test-work", "cellml-cmeta"));
    }

    @Test
    void aFileSourceIsEnrichedAndItsKeywordsAreFlattenedOntoTheAnnotation() throws Exception {
        var repository = new RecordingGraphRepository();

        var indexed = service(repository).indexUris(
                List.of(FIXTURES.resolve("local-model.cellml").toAbsolutePath().toUri()),
                new RemotePmrCellmlExternalCmetaResolver());

        assertEquals(1, indexed.size());
        var annotation = externalAnnotation(repository);
        assertTrue(String.valueOf(annotation.properties().get("title")).contains("External cmeta from a local file"),
                "annotation title: " + annotation.properties());

        var keywords = annotation.properties().get("keywords");
        assertNotNull(keywords, "the keywords carried by cmeta must be written: " + annotation.properties());
        // Nested groups concatenated in document order, one space between keywords, no List#toString().
        assertEquals("cardiac electrophysiology calcium_handling", keywords);
        assertFalse(keywords.contains("["), "nested lists must be flattened, not stringified: " + keywords);
    }

    @Test
    void withoutAResolverTheSameModelCarriesNoExternalCmeta() throws Exception {
        var repository = new RecordingGraphRepository();

        service(repository).indexUris(List.of(FIXTURES.resolve("local-model.cellml").toAbsolutePath().toUri()));

        assertTrue(externalAnnotations(repository).isEmpty(),
                "external cmeta is opt-in and must not be read without a resolver");
    }

    private CellmlModelIndexingService service(RecordingGraphRepository repository) {
        return new CellmlModelIndexingService(
                new ModelSourceDiscovery(),
                new UriModelSourceContentLoader(),
                new CellmlModelParser(),
                new ParsedModelGraphMapper(),
                repository);
    }

    private List<ExtractedGraphNode> externalAnnotations(RecordingGraphRepository repository) {
        var all = new ArrayList<ExtractedGraphNode>();
        repository.graphs.getFirst().nodes().stream()
                .filter(node -> node.labels().contains("ANNOTATION"))
                .forEach(all::add);
        return List.copyOf(all);
    }

    private ExtractedGraphNode externalAnnotation(RecordingGraphRepository repository) {
        var annotations = externalAnnotations(repository);
        assertEquals(1, annotations.size(), "expected one external-cmeta annotation, got: " + annotations);
        return annotations.getFirst();
    }

    private static final class RecordingGraphRepository implements ExtractedModelGraphRepository {
        private final ArrayList<ExtractedModelGraph> graphs = new ArrayList<>();

        @Override
        public ModelIdentifier insert(ExtractedModelGraph graph) {
            graphs.add(graph);
            return new ModelIdentifier("uid-" + graphs.size(), graph.reference().fileId());
        }

        @Override
        public void linkArchive(URI archiveUri, List<URI> entrySourceUris) {
            // Recording repository: archive linkage is exercised elsewhere.
        }
    }
}
