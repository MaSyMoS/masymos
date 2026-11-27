package org.masymos.service.config;

import org.masymos.core.archive.CombineArchiveIndexingService;
import org.masymos.core.extract.CellmlModelIndexingService;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ModelSourceDiscovery;
import org.masymos.core.extract.OwlOntologyIndexingService;
import org.masymos.core.extract.OwlOntologyParser;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.ParsedOntologyGraphMapper;
import org.masymos.core.extract.RemotePmrCellmlExternalCmetaResolver;
import org.masymos.core.extract.SbmlModelIndexingService;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.extract.SedmlModelIndexingService;
import org.masymos.core.extract.SedmlModelParser;
import org.masymos.core.extract.UriModelSourceContentLoader;
import org.masymos.core.spi.ExtractedModelGraphRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SedmlLinkProperties.class, StorageDocumentProperties.class, IndexBoundsProperties.class})
public class ExtractionConfiguration {
    @Bean
    ModelSourceDiscovery modelSourceDiscovery() {
        return new ModelSourceDiscovery();
    }

    @Bean
    UriModelSourceContentLoader uriModelSourceContentLoader() {
        return new UriModelSourceContentLoader();
    }

    @Bean
    SbmlModelParser sbmlModelParser() {
        return new SbmlModelParser();
    }

    @Bean
    CellmlModelParser cellmlModelParser() {
        return new CellmlModelParser();
    }

    @Bean
    RemotePmrCellmlExternalCmetaResolver remotePmrCellmlExternalCmetaResolver() {
        return new RemotePmrCellmlExternalCmetaResolver();
    }

    @Bean
    SedmlModelParser sedmlModelParser() {
        return new SedmlModelParser();
    }

    @Bean
    OwlOntologyParser owlOntologyParser() {
        return new OwlOntologyParser();
    }

    @Bean
    ParsedModelGraphMapper parsedModelGraphMapper() {
        return new ParsedModelGraphMapper();
    }

    @Bean
    ParsedOntologyGraphMapper parsedOntologyGraphMapper() {
        return new ParsedOntologyGraphMapper();
    }

    @Bean
    SbmlModelIndexingService sbmlModelIndexingService(
            ModelSourceDiscovery discovery,
            UriModelSourceContentLoader contentLoader,
            SbmlModelParser parser,
            ParsedModelGraphMapper graphMapper,
            ExtractedModelGraphRepository graphRepository,
            StorageDocumentProperties documentProperties
    ) {
        return new SbmlModelIndexingService(
                discovery, contentLoader, parser, graphMapper, graphRepository, documentProperties.maxBytesOrDefault());
    }

    @Bean
    CellmlModelIndexingService cellmlModelIndexingService(
            ModelSourceDiscovery discovery,
            UriModelSourceContentLoader contentLoader,
            CellmlModelParser parser,
            ParsedModelGraphMapper graphMapper,
            ExtractedModelGraphRepository graphRepository,
            StorageDocumentProperties documentProperties
    ) {
        return new CellmlModelIndexingService(
                discovery, contentLoader, parser, graphMapper, graphRepository, documentProperties.maxBytesOrDefault());
    }

    @Bean
    SedmlModelIndexingService sedmlModelIndexingService(
            ModelSourceDiscovery discovery,
            UriModelSourceContentLoader contentLoader,
            SedmlModelParser parser,
            ParsedModelGraphMapper graphMapper,
            ExtractedModelGraphRepository graphRepository,
            StorageDocumentProperties documentProperties
    ) {
        return new SedmlModelIndexingService(
                discovery, contentLoader, parser, graphMapper, graphRepository, documentProperties.maxBytesOrDefault());
    }

    @Bean
    CombineArchiveIndexingService combineArchiveIndexingService(
            SbmlModelIndexingService sbmlModelIndexingService,
            CellmlModelIndexingService cellmlModelIndexingService,
            SedmlModelIndexingService sedmlModelIndexingService,
            ExtractedModelGraphRepository graphRepository
    ) {
        return new CombineArchiveIndexingService(
                sbmlModelIndexingService, cellmlModelIndexingService, sedmlModelIndexingService, graphRepository);
    }

    @Bean
    OwlOntologyIndexingService owlOntologyIndexingService(
            ModelSourceDiscovery discovery,
            UriModelSourceContentLoader contentLoader,
            OwlOntologyParser parser,
            ParsedOntologyGraphMapper graphMapper,
            ExtractedModelGraphRepository graphRepository,
            StorageDocumentProperties documentProperties
    ) {
        return new OwlOntologyIndexingService(
                discovery, contentLoader, parser, graphMapper, graphRepository, documentProperties.maxBytesOrDefault());
    }
}
