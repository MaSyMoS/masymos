package org.masymos.service.config;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.model.ModelDeletionResult;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelVersion;
import org.masymos.core.model.StoredDocument;
import org.masymos.core.query.AnnotationSearchResult;
import org.masymos.core.query.ArchiveSearchResult;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.query.Page;
import org.masymos.core.query.PersonSearchResult;
import org.masymos.core.query.PublicationSearchResult;
import org.masymos.core.query.SedmlSearchResult;
import org.masymos.core.rederive.ReDeriveReport;
import org.masymos.core.spi.AnnotationIndexService;
import org.masymos.core.spi.ArchiveQueryRepository;
import org.masymos.core.spi.ExtractedModelGraphRepository;
import org.masymos.core.spi.IndexCatalog;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.ModelRowLookup;
import org.masymos.core.spi.OntologySimilarity;
import org.masymos.core.spi.OntologyTerm;
import org.masymos.core.spi.OntologyTraversal;
import org.masymos.core.spi.ReDerive;
import org.masymos.core.spi.ResourceQueryRepository;
import org.masymos.core.spi.SchemaInitializer;
import org.masymos.core.spi.SedmlLinkingRepository;
import org.masymos.core.spi.SedmlQueryRepository;
import org.masymos.core.spi.StatisticsRepository;
import org.masymos.core.spi.UnitBackfill;
import org.masymos.service.api.StorageUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "masymos.neo4j", name = "enabled", havingValue = "false")
public class DisabledStorageConfiguration {
    @Bean
    ModelRepository disabledModelRepository() {
        return new DisabledModelRepository();
    }

    @Bean
    ExtractedModelGraphRepository disabledExtractedModelGraphRepository() {
        return new DisabledExtractedModelGraphRepository();
    }

    @Bean
    ResourceQueryRepository disabledResourceQueryRepository() {
        return new DisabledResourceQueryRepository();
    }

    @Bean
    SchemaInitializer disabledSchemaInitializer() {
        return () -> {
        };
    }

    @Bean
    IndexCatalog disabledIndexCatalog() {
        return () -> {
            throw new StorageUnavailableException("model storage is disabled");
        };
    }

    @Bean
    StatisticsRepository disabledStatisticsRepository() {
        return () -> {
            throw new StorageUnavailableException("model storage is disabled");
        };
    }

    @Bean
    SedmlLinkingRepository disabledSedmlLinkingRepository() {
        return () -> {
            throw new StorageUnavailableException("model storage is disabled");
        };
    }

    @Bean
    SedmlQueryRepository disabledSedmlQueryRepository() {
        return new DisabledSedmlQueryRepository();
    }

    @Bean
    ArchiveQueryRepository disabledArchiveQueryRepository() {
        return new DisabledArchiveQueryRepository();
    }

    @Bean
    AnnotationIndexService disabledAnnotationIndexService() {
        return options -> {
            throw new StorageUnavailableException("model storage is disabled");
        };
    }

    @Bean
    ModelRowLookup disabledModelRowLookup() {
        return documentUris -> {
            throw new StorageUnavailableException("model storage is disabled");
        };
    }

    @Bean
    UnitBackfill disabledUnitBackfill() {
        return (apply, limit, cursor) -> {
            throw new StorageUnavailableException("model storage is disabled");
        };
    }

    @Bean
    ReDerive disabledReDerive() {
        return new ReDerive() {
            @Override
            public ReDeriveReport run(int limit, String cursor) {
                throw unavailable();
            }

            /** The repair is a store operation too: without a store it is the same refusal, not a 500. */
            @Override
            public ReDeriveReport apply(int limit, String cursor) {
                throw unavailable();
            }

            private StorageUnavailableException unavailable() {
                return new StorageUnavailableException("model storage is disabled");
            }
        };
    }

    @Bean
    OntologyTraversal disabledOntologyTraversal() {
        return new OntologyTraversal() {
            @Override
            public Optional<OntologyTerm> term(String ontology, String id) {
                throw disabled();
            }

            @Override
            public Optional<OntologyTerm> root(String ontology) {
                throw disabled();
            }

            @Override
            public Optional<Integer> depth(String ontology, String id, int maxDepth) {
                throw disabled();
            }

            @Override
            public Optional<OntologySimilarity> similarity(
                    String ontology, String first, String second, int maxDepth) {
                throw disabled();
            }

            private StorageUnavailableException disabled() {
                return new StorageUnavailableException("model storage is disabled");
            }
        };
    }

    private static final class DisabledModelRepository implements ModelRepository {
        @Override
        public ModelIdentifier insert(ModelReference modelReference) {
            throw unavailable();
        }

        @Override
        public ModelIdentifier insert(ModelReference modelReference, boolean enforceUniqueFileId) {
            throw unavailable();
        }

        @Override
        public Optional<ModelIdentifier> findByUid(String uid) {
            throw unavailable();
        }

        @Override
        public Optional<ModelVersion> findLatestVersion(String fileId) {
            throw unavailable();
        }

        @Override
        public List<ModelVersion> findVersions(String fileId) {
            throw unavailable();
        }

        @Override
        public Optional<ModelVersion> findVersion(String fileId, String versionId) {
            throw unavailable();
        }

        @Override
        public Optional<StoredDocument> findDocument(String uid) {
            throw unavailable();
        }

        @Override
        public List<ModelSearchResult> queryModels(ModelQueryRequest request) {
            throw unavailable();
        }

        @Override
        public Optional<ModelDeletionResult> deleteByUid(String uid, String expectedFileId, String expectedVersionId) {
            throw unavailable();
        }

        @Override
        public Optional<ModelDeletionResult> deleteByFileId(String fileId) {
            throw unavailable();
        }

        private StorageUnavailableException unavailable() {
            return new StorageUnavailableException("model storage is disabled");
        }
    }

    private static final class DisabledExtractedModelGraphRepository implements ExtractedModelGraphRepository {
        @Override
        public ModelIdentifier insert(ExtractedModelGraph graph) {
            throw new StorageUnavailableException("model storage is disabled");
        }

        @Override
        public void linkArchive(URI archiveUri, List<URI> entrySourceUris) {
            throw new StorageUnavailableException("model storage is disabled");
        }
    }

    private static final class DisabledResourceQueryRepository implements ResourceQueryRepository {
        @Override
        public List<AnnotationSearchResult> queryAnnotations(ModelQueryRequest request) {
            throw unavailable();
        }

        @Override
        public List<PersonSearchResult> queryPersons(ModelQueryRequest request) {
            throw unavailable();
        }

        @Override
        public List<PublicationSearchResult> queryPublications(ModelQueryRequest request) {
            throw unavailable();
        }

        @Override
        public Page<AnnotationSearchResult> queryAnnotationsPage(ModelQueryRequest request) {
            throw unavailable();
        }

        @Override
        public Page<PersonSearchResult> queryPersonsPage(ModelQueryRequest request) {
            throw unavailable();
        }

        @Override
        public Page<PublicationSearchResult> queryPublicationsPage(ModelQueryRequest request) {
            throw unavailable();
        }

        private StorageUnavailableException unavailable() {
            return new StorageUnavailableException("model storage is disabled");
        }
    }

    private static final class DisabledSedmlQueryRepository implements SedmlQueryRepository {
        @Override
        public List<SedmlSearchResult> querySedml(ModelQueryRequest request) {
            throw unavailable();
        }

        @Override
        public Page<SedmlSearchResult> querySedmlPage(ModelQueryRequest request) {
            throw unavailable();
        }

        private StorageUnavailableException unavailable() {
            return new StorageUnavailableException("model storage is disabled");
        }
    }

    private static final class DisabledArchiveQueryRepository implements ArchiveQueryRepository {
        @Override
        public List<ArchiveSearchResult> queryArchives(ModelQueryRequest request) {
            throw unavailable();
        }

        @Override
        public Page<ArchiveSearchResult> queryArchivesPage(ModelQueryRequest request) {
            throw unavailable();
        }

        private StorageUnavailableException unavailable() {
            return new StorageUnavailableException("model storage is disabled");
        }
    }
}
