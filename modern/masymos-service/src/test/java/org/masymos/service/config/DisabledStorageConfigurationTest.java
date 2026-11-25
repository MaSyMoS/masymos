package org.masymos.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.masymos.core.spi.IndexCatalog;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.SchemaInitializer;
import org.masymos.core.spi.StatisticsRepository;
import org.masymos.neo4j.Neo4jModelRepository;
import org.masymos.service.MaSyMoSApplication;
import org.masymos.service.api.HealthController;
import org.masymos.service.api.StorageUnavailableException;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        classes = MaSyMoSApplication.class,
        properties = "masymos.neo4j.enabled=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class DisabledStorageConfigurationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void startsWithoutNeo4jDriverWhenStorageIsDisabled() {
        assertThat(applicationContext.getBean(ModelRepository.class)).isNotInstanceOf(Neo4jModelRepository.class);
        assertThat(applicationContext.getBean(SchemaInitializer.class)).isNotNull();
        assertThat(applicationContext.getBeansOfType(Driver.class)).isEmpty();
    }

    @Test
    void healthAndMetadataControllersCanBeCreatedWithoutNeo4j() {
        assertThat(applicationContext.getBean("healthController")).isNotNull();
        assertThat(applicationContext.getBean("queryMetadataController")).isNotNull();
        assertThat(applicationContext.getBean(HealthController.class).health().getStatusCode().is2xxSuccessful())
                .isTrue();
    }

    @Test
    void theIndexCatalogueRefusesToAnswerWithoutStorage() {
        var catalog = applicationContext.getBean(IndexCatalog.class);

        assertThatThrownBy(catalog::list).isInstanceOf(StorageUnavailableException.class);
    }

    @Test
    void theStatisticsRefuseToAnswerWithoutStorage() {
        var statistics = applicationContext.getBean(StatisticsRepository.class);

        assertThatThrownBy(statistics::statistics).isInstanceOf(StorageUnavailableException.class);
    }
}
