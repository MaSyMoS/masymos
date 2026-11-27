package org.masymos.core.extract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.masymos.core.model.ModelType;
import org.masymos.core.spi.ExtractedModelGraphRepository;

public final class CellmlModelIndexingService {
    private final ModelSourceDiscovery discovery;
    private final ModelSourceContentLoader contentLoader;
    private final CellmlModelParser parser;
    private final ParsedModelGraphMapper graphMapper;
    private final ExtractedModelGraphRepository graphRepository;
    private final int maxDocumentBytes;

    public CellmlModelIndexingService(
            ModelSourceDiscovery discovery,
            ModelSourceContentLoader contentLoader,
            CellmlModelParser parser,
            ParsedModelGraphMapper graphMapper,
            ExtractedModelGraphRepository graphRepository
    ) {
        this(discovery, contentLoader, parser, graphMapper, graphRepository, SourceDocument.DEFAULT_MAX_BYTES);
    }

    public CellmlModelIndexingService(
            ModelSourceDiscovery discovery,
            ModelSourceContentLoader contentLoader,
            CellmlModelParser parser,
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
        return indexSources(discovery.fromModelFiles(directory, ModelType.CELLML));
    }

    public List<IndexedModel> indexListFiles(Path directory) throws IOException {
        return indexSources(discovery.fromListFiles(directory, ModelType.CELLML));
    }

    public List<IndexedModel> indexUris(List<URI> sourceUris) throws IOException {
        return indexSources(discovery.fromUris(sourceUris, ModelType.CELLML));
    }

    public List<IndexedModel> indexUris(List<URI> sourceUris, CellmlExternalCmetaResolver externalCmetaResolver)
            throws IOException {
        return indexSources(discovery.fromUris(sourceUris, ModelType.CELLML), externalCmetaResolver);
    }

    /** See {@link #indexSources(List, CellmlExternalCmetaResolver, CellmlImportResolver)}. */
    public List<IndexedModel> indexUris(
            List<URI> sourceUris,
            CellmlExternalCmetaResolver externalCmetaResolver,
            CellmlImportResolver importResolver
    ) throws IOException {
        return indexSources(discovery.fromUris(sourceUris, ModelType.CELLML), externalCmetaResolver, importResolver);
    }

    public List<IndexedModel> indexSources(List<ModelSource> sources) throws IOException {
        return indexSources(sources, parser);
    }

    public List<IndexedModel> indexSources(List<ModelSource> sources, CellmlExternalCmetaResolver externalCmetaResolver)
            throws IOException {
        return indexSources(sources, new CellmlModelParser(externalCmetaResolver));
    }

    /**
     * Indexes with both opt-in resolvers (CML-2). Neither the REST surface nor the CLI exposes an
     * import resolver yet, so this overload is what a caller with the import targets at hand uses;
     * without it the capability would exist only inside {@link CellmlModelParser}.
     */
    public List<IndexedModel> indexSources(
            List<ModelSource> sources,
            CellmlExternalCmetaResolver externalCmetaResolver,
            CellmlImportResolver importResolver
    ) throws IOException {
        return indexSources(sources, new CellmlModelParser(externalCmetaResolver, importResolver));
    }

    private List<IndexedModel> indexSources(List<ModelSource> sources, CellmlModelParser cellmlParser) throws IOException {
        var indexedModels = new ArrayList<IndexedModel>();
        for (var source : sources) {
            indexedModels.add(indexSource(source, cellmlParser));
        }
        return List.copyOf(indexedModels);
    }

    private IndexedModel indexSource(ModelSource source, CellmlModelParser cellmlParser) throws IOException {
        try (var stream = contentLoader.open(source)) {
            var bytes = stream.readAllBytes();
            var parsedModel = cellmlParser.parse(source, new ByteArrayInputStream(bytes));
            var graph = graphMapper.toGraph(parsedModel).withSourceDocument(SourceDocument.from(bytes, maxDocumentBytes));
            var identifier = graphRepository.insert(graph);
            return new IndexedModel(source, identifier);
        }
    }
}
