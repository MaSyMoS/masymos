package org.masymos.core.extract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.masymos.core.model.ModelType;
import org.masymos.core.spi.ExtractedModelGraphRepository;

public final class SedmlModelIndexingService {
    private final ModelSourceDiscovery discovery;
    private final ModelSourceContentLoader contentLoader;
    private final SedmlModelParser parser;
    private final ParsedModelGraphMapper graphMapper;
    private final ExtractedModelGraphRepository graphRepository;
    private final int maxDocumentBytes;

    public SedmlModelIndexingService(
            ModelSourceDiscovery discovery,
            ModelSourceContentLoader contentLoader,
            SedmlModelParser parser,
            ParsedModelGraphMapper graphMapper,
            ExtractedModelGraphRepository graphRepository
    ) {
        this(discovery, contentLoader, parser, graphMapper, graphRepository, SourceDocument.DEFAULT_MAX_BYTES);
    }

    public SedmlModelIndexingService(
            ModelSourceDiscovery discovery,
            ModelSourceContentLoader contentLoader,
            SedmlModelParser parser,
            ParsedModelGraphMapper graphMapper,
            ExtractedModelGraphRepository graphRepository,
            int maxDocumentBytes
    ) {
        if (maxDocumentBytes < 1) {
            throw new IllegalArgumentException("maxDocumentBytes must be positive");
        }
        this.discovery = discovery;
        this.contentLoader = contentLoader;
        this.parser = parser;
        this.graphMapper = graphMapper;
        this.graphRepository = graphRepository;
        this.maxDocumentBytes = maxDocumentBytes;
    }

    public List<IndexedModel> indexModelFiles(Path directory) throws IOException {
        return indexSources(discovery.fromModelFiles(directory, ModelType.SEDML));
    }

    public List<IndexedModel> indexListFiles(Path directory) throws IOException {
        return indexSources(discovery.fromListFiles(directory, ModelType.SEDML));
    }

    public List<IndexedModel> indexUris(List<URI> sourceUris) throws IOException {
        return indexSources(discovery.fromUris(sourceUris, ModelType.SEDML));
    }

    public List<IndexedModel> indexSources(List<ModelSource> sources) throws IOException {
        var indexedModels = new ArrayList<IndexedModel>();
        for (var source : sources) {
            indexedModels.add(indexSource(source));
        }
        return List.copyOf(indexedModels);
    }

    private IndexedModel indexSource(ModelSource source) throws IOException {
        try (var stream = contentLoader.open(source)) {
            var bytes = stream.readAllBytes();
            var parsedModel = parser.parse(source, new ByteArrayInputStream(bytes));
            var graph = graphMapper.toGraph(parsedModel).withSourceDocument(SourceDocument.from(bytes, maxDocumentBytes));
            var identifier = graphRepository.insert(graph);
            return new IndexedModel(source, identifier);
        }
    }
}
