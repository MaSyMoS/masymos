package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Neo4jIndexCatalogTest {
    @Test
    void theCypherAsksTheStoreForItsOwnCatalogue() {
        var cypher = Neo4jIndexCatalog.listCypher();

        assertTrue(cypher.startsWith("SHOW INDEXES"), cypher);
        assertTrue(cypher.contains("YIELD name, type, entityType, labelsOrTypes, properties, state"), cypher);
        assertTrue(cypher.contains("ORDER BY name"), cypher);
    }

    @Test
    void aRowBecomesTheReportedSummary() {
        var summary = Neo4jIndexCatalog.toSummary(Map.of(
                "name", "modelFulltextV5",
                "type", "FULLTEXT",
                "entityType", "NODE",
                "labelsOrTypes", List.of("Model"),
                "properties", List.of("fileId", "name"),
                "state", "ONLINE"));

        assertEquals("modelFulltextV5", summary.name());
        assertEquals("FULLTEXT", summary.type());
        assertEquals("NODE", summary.entityType());
        assertEquals(List.of("Model"), summary.labelsOrTypes());
        assertEquals(List.of("fileId", "name"), summary.properties());
        assertEquals("ONLINE", summary.state());
    }

    @Test
    void aRowWithoutListsReportsEmptyListsRatherThanFailing() {
        var summary = Neo4jIndexCatalog.toSummary(Map.of("name", "document_uid", "type", "RANGE"));

        assertEquals("document_uid", summary.name());
        assertEquals(List.of(), summary.labelsOrTypes());
        assertEquals(List.of(), summary.properties());
        assertNull(summary.state());
    }
}
