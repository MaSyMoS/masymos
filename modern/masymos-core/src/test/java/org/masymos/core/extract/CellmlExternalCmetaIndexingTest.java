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
 * carried but never written now reach the graph as flattened text on the external-cmeta annotation,
 * which {@code ParsedModelGraphMapper} additionally aggregates onto the model as the {@code keywords}
 * search property that {@code modelFulltextV6} indexes (decision 27).
 *
 * <p>Since decision 44 that annotation also carries its provenance, {@code provenance =
 * cellml.externalCmeta}: the graph records that the metadata came from beside the document instead of
 * from it, which nothing else in the annotation's shape can say.
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

        // CORP-10 (row CORP7-7): the corpus lane must see that the resolved external document
        // contributed, not infer it from an annotation it cannot tell from an in-document one.
        assertEquals("read", documentNode(repository).properties().get("cellml.externalCmeta"),
                documentNode(repository).properties().toString());

        // Decision 44: the annotation says where it came from, so the graph itself can tell.
        assertEquals("cellml.externalCmeta", annotation.properties().get("provenance"),
                annotation.properties().toString());
    }

    /**
     * Decision 44, both directions: the annotation the external cmeta produced names its origin, and
     * an annotation the model writes about itself - same shape, same subject - does not. Without the
     * second half the property would prove nothing, because every annotation would carry it.
     */
    @Test
    void onlyTheAnnotationFromTheExternalCmetaCarriesItsProvenance() throws Exception {
        var repository = new RecordingGraphRepository();
        CellmlExternalCmetaResolver resolver = source -> List.of(new CellmlExternalCmeta(
                null, List.of(List.of("external-marker")), null, List.of(),
                "External cmeta title", null, null, null));

        // One model, two annotations: one the model writes about itself, one the resolver answered.
        // Both hang off the same subject, so the graph cannot tell them apart by shape.
        var file = Path.of("target", "test-work", "cellml-cmeta", "annotated.cellml");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.cellml.org/cellml/1.0#"
                       xmlns:cmeta="http://www.cellml.org/metadata/1.0#"
                       xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                       name="annotated" cmeta:id="annotated_model">
                  <component name="cell"/>
                  <rdf:RDF>
                    <rdf:Description rdf:about="#annotated_model">
                      <reference rdf:resource="urn:test:own-annotation"/>
                    </rdf:Description>
                  </rdf:RDF>
                </model>
                """);

        service(repository).indexUris(List.of(file.toAbsolutePath().toUri()), resolver);

        var annotations = annotations(repository);
        var fromCmeta = annotations.stream()
                .filter(node -> "cellml.externalCmeta".equals(node.properties().get("provenance")))
                .toList();
        assertEquals(1, fromCmeta.size(),
                "expected exactly one external-cmeta annotation in: " + annotations);
        assertEquals("External cmeta title", fromCmeta.getFirst().properties().get("title"));
        assertEquals(2, annotations.size(),
                "the model's own annotation must exist beside the external one, or the negative half "
                        + "of this test proves nothing: " + annotations);
        assertTrue(nodes(repository).stream()
                        .anyMatch(node -> "urn:test:own-annotation".equals(node.properties().get("uri"))),
                "the model's own annotation must still be written: " + nodes(repository));
    }

    /**
     * PMR answers a known model with an all-null skeleton and an empty keyword list. That answer
     * exists but contributes nothing, so the marker must stay absent: otherwise "the resolver
     * answered" would be reported as "the resolver enriched".
     */
    @Test
    void aResolverAnsweringAnEmptySkeletonCarriesNoMarker() throws Exception {
        var repository = new RecordingGraphRepository();
        CellmlExternalCmetaResolver emptySkeleton = source -> List.of(new CellmlExternalCmeta(
                null, List.of(), null, List.of(), null, null, null, null));

        service(repository).indexUris(
                List.of(FIXTURES.resolve("local-model.cellml").toAbsolutePath().toUri()),
                emptySkeleton);

        assertTrue(annotations(repository).isEmpty(),
                "an all-null skeleton must not become an annotation");
        assertFalse(documentNode(repository).properties().containsKey("cellml.externalCmeta"),
                documentNode(repository).properties().toString());
    }

    @Test
    void withoutAResolverTheSameModelCarriesNoExternalCmeta() throws Exception {
        var repository = new RecordingGraphRepository();

        service(repository).indexUris(List.of(FIXTURES.resolve("local-model.cellml").toAbsolutePath().toUri()));

        assertTrue(annotations(repository).isEmpty(),
                "external cmeta is opt-in and must not be read without a resolver");
        assertFalse(documentNode(repository).properties().containsKey("cellml.externalCmeta"),
                "the default resolver never reads anything, so it cannot have been read: "
                        + documentNode(repository).properties());
    }

    private CellmlModelIndexingService service(RecordingGraphRepository repository) {
        return new CellmlModelIndexingService(
                new ModelSourceDiscovery(),
                new UriModelSourceContentLoader(),
                new CellmlModelParser(),
                new ParsedModelGraphMapper(),
                repository);
    }

    private List<ExtractedGraphNode> annotations(RecordingGraphRepository repository) {
        var all = new ArrayList<ExtractedGraphNode>();
        repository.graphs.getFirst().nodes().stream()
                .filter(node -> node.labels().contains("ANNOTATION"))
                .forEach(all::add);
        return List.copyOf(all);
    }

    private ExtractedGraphNode documentNode(RecordingGraphRepository repository) {
        var graph = repository.graphs.getFirst();
        return graph.nodes().stream()
                .filter(node -> node.labels().contains("Document"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Document node in: " + graph.nodes()));
    }

    private List<ExtractedGraphNode> nodes(RecordingGraphRepository repository) {
        return List.copyOf(repository.graphs.getFirst().nodes());
    }

    private ExtractedGraphNode externalAnnotation(RecordingGraphRepository repository) {
        var annotations = annotations(repository);
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
