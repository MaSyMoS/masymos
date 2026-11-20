package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class Neo4jStatisticsRepositoryTest {
    @Test
    void theCypherCountsEveryLabelInOneRead() {
        var cypher = Neo4jStatisticsRepository.statisticsCypher();

        for (var label : new String[] {
            "Document", "Model", "SBML_MODEL", "CELLML_MODEL", "SEDML_MODEL", "OWL_MODEL",
            "PERSON", "PUBLICATION", "RESOURCE", "CombineArchive"
        }) {
            assertTrue(cypher.contains("MATCH (n:" + label + ")"), label + " missing from: " + cypher);
        }
        assertTrue(cypher.contains("RETURN documents, models, sbmlModels"), cypher);
    }

    @Test
    void aRowBecomesTheReportedCounts() {
        var statistics = Neo4jStatisticsRepository.toStatistics(Map.of(
                "documents", 12L,
                "models", 9L,
                "sbmlModels", 5L,
                "cellmlModels", 2L,
                "sedmlModels", 1L,
                "owlModels", 1L,
                "persons", 7L,
                "publications", 3L,
                "annotations", 40L,
                "archives", 2L));

        assertEquals(12L, statistics.documents());
        assertEquals(9L, statistics.models());
        assertEquals(5L, statistics.sbmlModels());
        assertEquals(2L, statistics.cellmlModels());
        assertEquals(1L, statistics.sedmlModels());
        assertEquals(1L, statistics.owlModels());
        assertEquals(7L, statistics.persons());
        assertEquals(3L, statistics.publications());
        assertEquals(40L, statistics.annotations());
        assertEquals(2L, statistics.archives());
    }

    @Test
    void anAbsentCountIsZeroRatherThanAFailure() {
        var statistics = Neo4jStatisticsRepository.toStatistics(Map.of());

        assertEquals(0L, statistics.documents());
        assertEquals(0L, statistics.archives());
    }
}
