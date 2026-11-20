package org.masymos.service.config;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.masymos.core.annotation.AnnotationPrefixCatalogue;
import org.masymos.core.annotation.AnnotationTermProvider;
import org.masymos.core.annotation.AnnotationTermResolver;
import org.masymos.core.annotation.AnnotationTextExtractor;
import org.masymos.core.annotation.HttpAnnotationResourceFetcher;
import org.masymos.core.spi.AnnotationIndexService;
import org.masymos.core.spi.ArchiveQueryRepository;
import org.masymos.core.spi.ExtractedModelGraphRepository;
import org.masymos.core.spi.IndexCatalog;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.ModelRowLookup;
import org.masymos.core.spi.OntologyTraversal;
import org.masymos.core.spi.ResourceQueryRepository;
import org.masymos.core.spi.SchemaInitializer;
import org.masymos.core.spi.SedmlLinkingRepository;
import org.masymos.core.spi.SedmlQueryRepository;
import org.masymos.core.spi.StatisticsRepository;
import org.masymos.core.spi.UnitBackfill;
import org.masymos.neo4j.Neo4jAnnotationIndexService;
import org.masymos.neo4j.Neo4jAnnotationTermCache;
import org.masymos.neo4j.Neo4jArchiveQueryRepository;
import org.masymos.neo4j.Neo4jDriverFactory;
import org.masymos.neo4j.Neo4jExtractedModelGraphRepository;
import org.masymos.neo4j.Neo4jIndexCatalog;
import org.masymos.neo4j.Neo4jModelRepository;
import org.masymos.neo4j.Neo4jModelRowLookup;
import org.masymos.neo4j.Neo4jOntologyTraversal;
import org.masymos.neo4j.Neo4jResourceQueryRepository;
import org.masymos.neo4j.Neo4jSchemaInitializer;
import org.masymos.neo4j.Neo4jSedmlLinkingRepository;
import org.masymos.neo4j.Neo4jSedmlQueryRepository;
import org.masymos.neo4j.Neo4jSettings;
import org.masymos.neo4j.Neo4jStatisticsRepository;
import org.masymos.neo4j.Neo4jUnitBackfill;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Neo4jProperties.class)
@ConditionalOnProperty(prefix = "masymos.neo4j", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Neo4jConfiguration {
    @Bean(destroyMethod = "close")
    Driver neo4jDriver(Neo4jProperties properties) {
        var settings = new Neo4jSettings(
                URI.create(properties.uri()),
                properties.username(),
                properties.password(),
                properties.database()
        );
        return Neo4jDriverFactory.create(settings);
    }

    @Bean
    ModelRepository modelRepository(Driver driver, Neo4jProperties properties) {
        return new Neo4jModelRepository(driver, properties.database());
    }

    @Bean
    ExtractedModelGraphRepository extractedModelGraphRepository(Driver driver, Neo4jProperties properties) {
        return new Neo4jExtractedModelGraphRepository(driver, properties.database());
    }

    @Bean
    ResourceQueryRepository resourceQueryRepository(Driver driver, Neo4jProperties properties) {
        return new Neo4jResourceQueryRepository(driver, properties.database());
    }

    @Bean
    SedmlLinkingRepository sedmlLinkingRepository(Driver driver, Neo4jProperties properties) {
        return new Neo4jSedmlLinkingRepository(driver, properties.database());
    }

    @Bean
    SedmlQueryRepository sedmlQueryRepository(Driver driver, Neo4jProperties properties) {
        return new Neo4jSedmlQueryRepository(driver, properties.database());
    }

    @Bean
    ArchiveQueryRepository archiveQueryRepository(Driver driver, Neo4jProperties properties) {
        return new Neo4jArchiveQueryRepository(driver, properties.database());
    }

    @Bean
    AnnotationIndexService annotationIndexService(
            Driver driver,
            Neo4jProperties properties,
            List<AnnotationTermProvider> annotationTermProviders,
            @Value("${masymos.annotations.terms.ttl:P30D}") String termsTtl
    ) {
        var resolver = new AnnotationTermResolver(
                AnnotationPrefixCatalogue.standard(),
                annotationTermProviders,
                new Neo4jAnnotationTermCache(driver, properties.database()),
                Clock.systemUTC(),
                termsTtl(termsTtl),
                new HttpAnnotationResourceFetcher(),
                new AnnotationTextExtractor(),
                AnnotationTextExtractor.DEFAULT_MAX_TEXT_LENGTH);
        return new Neo4jAnnotationIndexService(
                driver,
                properties.database(),
                resolver,
                AnnotationPrefixCatalogue.standard());
    }

    /**
     * The configured term TTL; an unparsable value falls back to the resolver's default ({@code P30D})
     * instead of failing startup, because a cache that lives too long is a stale answer, not an outage.
     */
    private static Duration termsTtl(String termsTtl) {
        if (termsTtl == null || termsTtl.isBlank()) {
            return AnnotationTermResolver.DEFAULT_TTL;
        }
        try {
            return Duration.parse(termsTtl.trim());
        } catch (DateTimeParseException exception) {
            return AnnotationTermResolver.DEFAULT_TTL;
        }
    }

    @Bean
    ModelRowLookup modelRowLookup(Driver driver, Neo4jProperties properties) {
        return new Neo4jModelRowLookup(driver, properties.database());
    }

    @Bean
    OntologyTraversal ontologyTraversal(Driver driver, Neo4jProperties properties) {
        return new Neo4jOntologyTraversal(driver, properties.database());
    }

    @Bean
    SchemaInitializer schemaInitializer(Driver driver, Neo4jProperties properties) {
        return new Neo4jSchemaInitializer(driver, properties.database());
    }

    @Bean
    IndexCatalog indexCatalog(Driver driver, Neo4jProperties properties) {
        return new Neo4jIndexCatalog(driver, properties.database());
    }

    @Bean
    StatisticsRepository statisticsRepository(Driver driver, Neo4jProperties properties) {
        return new Neo4jStatisticsRepository(driver, properties.database());
    }

    @Bean
    UnitBackfill unitBackfill(Driver driver, Neo4jProperties properties) {
        return new Neo4jUnitBackfill(driver, properties.database());
    }
}
