package org.masymos.service.config;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ModelSourceDiscovery;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.SbmlModelIndexingService;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.spi.ExtractedModelGraphRepository;
import org.masymos.service.api.ApiExceptionHandler;
import org.masymos.service.api.SbmlIndexController;
import org.masymos.service.api.dto.IndexModelsRequest;
import org.springframework.http.MediaType;

/**
 * T-018 / GAP02-002: an index request is bounded at its boundary, before the first write.
 *
 * <p>The RED this pins is the absence of any bound: at {@code d04f73e} a 101-source request is accepted
 * and reaches the indexing service, which then reads, parses and writes 101 documents inside one
 * request thread.
 */
class IndexBoundsPropertiesTest {
    @Test
    void refusesASourceListAboveTheConfiguredCapBeforeAnyWrite() throws Exception {
        var writes = new AtomicInteger();
        var bounds = new IndexBoundsProperties(2, IndexBoundsProperties.DEFAULT_MAX_RESPONSE_BYTES);
        var controller = new SbmlIndexController(serviceThatRecordsWrites(writes), bounds);

        standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/api/v1/indexes/sbml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://masymos.org/problems/invalid-argument"))
                .andExpect(jsonPath("$.detail").value(containsString("at most 2 sources")));

        assertEquals(0, writes.get(), "a refused request must reach no repository write");
    }

    @Test
    void admitsARequestExactlyAtTheCap() {
        var bounds = new IndexBoundsProperties(3, IndexBoundsProperties.DEFAULT_MAX_RESPONSE_BYTES);

        assertDoesNotThrow(() -> request(3).requireWithin(bounds));
    }

    @Test
    void defaultsAreTheDocumentedValues() {
        var defaults = IndexBoundsProperties.defaults();

        assertEquals(100, defaults.maxSourcesPerRequestOrDefault());
        assertEquals(52_428_800L, defaults.maxResponseBytesOrDefault());
        assertEquals(100, new IndexBoundsProperties(null, null).maxSourcesPerRequestOrDefault());
        assertEquals(52_428_800L, new IndexBoundsProperties(null, null).maxResponseBytesOrDefault());
    }

    /** The service is only there to prove the request never gets past the boundary. */
    private static SbmlModelIndexingService serviceThatRecordsWrites(AtomicInteger writes) {
        return new SbmlModelIndexingService(
                new ModelSourceDiscovery(),
                source -> {
                    throw new AssertionError("a refused request must not read a source: " + source);
                },
                new SbmlModelParser(),
                new ParsedModelGraphMapper(),
                new ExtractedModelGraphRepository() {
                    @Override
                    public ModelIdentifier insert(ExtractedModelGraph graph) {
                        writes.incrementAndGet();
                        return new ModelIdentifier("uid-" + writes.get(), "file");
                    }

                    @Override
                    public void linkArchive(URI archiveUri, List<URI> entrySourceUris) {
                        writes.incrementAndGet();
                    }
                },
                4096);
    }

    private static IndexModelsRequest request(int sources) {
        return new IndexModelsRequest(IntStream.range(0, sources)
                .mapToObj(index -> URI.create("file:///models/m" + index + ".sbml"))
                .toList());
    }

    private static String body(int sources) {
        var uris = IntStream.range(0, sources)
                .mapToObj(index -> "\"file:///models/m" + index + ".sbml\"")
                .collect(Collectors.joining(", "));
        return "{\"sources\": [" + uris + "]}";
    }
}
