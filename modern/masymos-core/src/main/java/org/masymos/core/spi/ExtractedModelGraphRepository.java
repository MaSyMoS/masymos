package org.masymos.core.spi;


import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.model.ModelIdentifier;

public interface ExtractedModelGraphRepository {
    ModelIdentifier insert(ExtractedModelGraph graph);

    void linkArchive(java.net.URI archiveUri, java.util.List<java.net.URI> entrySourceUris);
}
