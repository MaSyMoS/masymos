package org.masymos.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.SbmlModelIndexingService;
import org.masymos.core.extract.SourceDocument;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.spi.ExtractedModelGraphRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Proves the configured ceiling reaches the indexing services and flags an oversized document,
 * rather than only proving the property record parses (STO-2).
 */
class ExtractionConfigurationTest {
    private static final String SBML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sbml xmlns="http://www.sbml.org/sbml/level3/version2/core" level="3" version="2">
              <model id="demo_model" name="Demo model">
                <listOfCompartments>
                  <compartment id="cell" constant="true"/>
                </listOfCompartments>
              </model>
            </sbml>
            """;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ExtractionConfiguration.class)
            .withBean(ExtractedModelGraphRepository.class, RecordingRepository::new);

    @Test
    void defaultsTheDocumentCeilingToTenMebibytes() {
        runner.run(context -> assertThat(context.getBean(StorageDocumentProperties.class).maxBytesOrDefault())
                .isEqualTo(SourceDocument.DEFAULT_MAX_BYTES));
    }

    @Test
    void appliesTheConfiguredCeilingWhenIndexing() throws Exception {
        var directory = Files.createDirectories(Path.of("target", "test-work", UUID.randomUUID().toString()));
        var file = directory.resolve("demo.sbml");
        Files.writeString(file, SBML, StandardCharsets.UTF_8);

        runner.withPropertyValues("masymos.storage.document.max-bytes=32")
                .run(context -> {
                    context.getBean(SbmlModelIndexingService.class).indexUris(List.of(file.toUri()));

                    var document = context.getBean(RecordingRepository.class)
                            .graphs.getFirst()
                            .sourceDocument();
                    assertThat(document).isNotNull();
                    assertThat(document.truncated()).isTrue();
                    assertThat(document.originalBytes()).isEqualTo(Files.size(file));
                    assertThat(document.contentBytes()).hasSize(32);
                });
    }

    private static final class RecordingRepository implements ExtractedModelGraphRepository {
        private final List<ExtractedModelGraph> graphs = new ArrayList<>();

        @Override
        public ModelIdentifier insert(ExtractedModelGraph graph) {
            graphs.add(graph);
            return new ModelIdentifier("uid-" + graphs.size(), graph.reference().fileId());
        }

        @Override
        public void linkArchive(URI archiveUri, List<URI> entrySourceUris) {
        }
    }
}
