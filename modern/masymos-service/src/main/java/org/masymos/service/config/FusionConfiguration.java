package org.masymos.service.config;

import java.util.List;
import org.masymos.core.query.fusion.BestRankStrategy;
import org.masymos.core.query.fusion.BordaCombMnzStrategy;
import org.masymos.core.query.fusion.RankFusionQueryService;
import org.masymos.core.query.fusion.RankFusionStrategy;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.spi.ModelRowLookup;
import org.masymos.core.spi.ResourceQueryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rank-fusion composition. The strategies and the query service live in {@code masymos-core}, which
 * carries no Spring dependency, so they are declared here; both storage configurations provide a
 * {@link ModelRowLookup}, so no condition is needed.
 */
@Configuration
public class FusionConfiguration {
    @Bean
    RankFusionStrategy bordaCombMnzStrategy() {
        return new BordaCombMnzStrategy();
    }

    @Bean
    RankFusionStrategy bestRankStrategy() {
        return new BestRankStrategy();
    }

    @Bean
    RankFusionQueryService rankFusionQueryService(
            ModelRepository models,
            ResourceQueryRepository resources,
            ModelRowLookup rows,
            List<RankFusionStrategy> strategies) {
        return new RankFusionQueryService(models, resources, rows, strategies);
    }
}
