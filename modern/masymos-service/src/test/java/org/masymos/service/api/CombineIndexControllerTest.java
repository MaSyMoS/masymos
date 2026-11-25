package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.masymos.core.archive.CombineArchiveIndexingService;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ModelSourceDiscovery;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.SbmlModelIndexingService;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.extract.SedmlModelIndexingService;
import org.masymos.core.extract.SedmlModelParser;
import org.masymos.core.extract.UriModelSourceContentLoader;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.spi.ExtractedModelGraphRepository;
import org.masymos.service.config.IndexBoundsProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

class CombineIndexControllerTest {
    private static final String MANIFEST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <omexManifest xmlns="http://identifiers.org/combine.specifications/omex-manifest">
              <content location="./" format="http://identifiers.org/combine.specifications/omex"/>
              <content location="becker1.sbml" format="http://identifiers.org/combine.specifications/sbml"/>
              <content location="metadata.rdf" format="http://identifiers.org/combine.specifications/rdf-metadata"/>
            </omexManifest>
            """;

    private static final String SBML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sbml xmlns="http://www.sbml.org/sbml/level3/version1/core" level="3" version="1">
              <model id="becker1"/>
            </sbml>
            """;

    @Test
    void indexesOneArchiveAndReportsCounters() throws Exception {
        var archive = writeArchive("becker.omex", "becker1.sbml", SBML, "metadata.rdf", "<rdf:RDF/>");
        var repository = new RecordingGraphRepository();

        mockMvc(realService(repository))
                .perform(request(archive.toUri()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourceUri").value(archive.toUri().toString()))
                .andExpect(jsonPath("$[0].indexedEntries").value(1))
                .andExpect(jsonPath("$[0].models.length()").value(1))
                .andExpect(jsonPath("$[0].models[0].fileId").value("becker1.sbml"))
                .andExpect(jsonPath("$[0].models[0].sourceUri").isNotEmpty());

        assertEquals(1, repository.graphs.size());
    }

    @Test
    void reportsSkippedEntries() throws Exception {
        var archive = writeArchive("becker-skipped.omex", "becker1.sbml", SBML, "metadata.rdf", "<rdf:RDF/>");

        mockMvc(realService(new RecordingGraphRepository()))
                .perform(request(archive.toUri()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].skipped.length()").value(1))
                .andExpect(jsonPath("$[0].skipped[0]").value(
                        "metadata.rdf: unsupported entry kind METADATA (format "
                                + "http://identifiers.org/combine.specifications/rdf-metadata)"));
    }

    @Test
    void respondsWithServiceUnavailableWhenStorageIsDisabled() throws Exception {
        var service = mock(CombineArchiveIndexingService.class);
        when(service.index(any())).thenThrow(new StorageUnavailableException("model storage is disabled"));

        mockMvc(service).perform(request(URI.create("file:///tmp/unavailable.omex")))
                .andExpect(status().isServiceUnavailable());

        verify(service).index(URI.create("file:///tmp/unavailable.omex"));
    }

    private static RequestBuilder request(URI source) {
        return post("/api/v1/indexes/combine")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sources": ["%s"]}
                        """.formatted(source));
    }

    private static MockMvc mockMvc(CombineArchiveIndexingService service) {
        return standaloneSetup(new CombineIndexController(service, IndexBoundsProperties.defaults()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static CombineArchiveIndexingService realService(ExtractedModelGraphRepository repository) {
        var discovery = new ModelSourceDiscovery();
        var contentLoader = new UriModelSourceContentLoader();
        var graphMapper = new ParsedModelGraphMapper();
        return new CombineArchiveIndexingService(
                new SbmlModelIndexingService(discovery, contentLoader, new SbmlModelParser(), graphMapper, repository),
                new CellmlModelIndexingService(
                        discovery, contentLoader, new CellmlModelParser(), graphMapper, repository),
                new SedmlModelIndexingService(
                        discovery, contentLoader, new SedmlModelParser(), graphMapper, repository),
                repository);
    }

    private static Path writeArchive(String filename, String... entries) throws IOException {
        var directory = Files.createDirectories(Path.of("target", "test-work", UUID.randomUUID().toString()));
        var archive = directory.resolve(filename);
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("manifest.xml"));
            zip.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int index = 0; index < entries.length; index += 2) {
                zip.putNextEntry(new ZipEntry(entries[index]));
                zip.write(entries[index + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
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
        }
    }
}
