package org.masymos.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ExtractedGraphNode;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ParsedAnnotation;
import org.masymos.core.extract.ParsedAnnotationResource;
import org.masymos.core.extract.ParsedModel;
import org.masymos.core.extract.ParsedModelEntity;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.ParsedPerson;
import org.masymos.core.extract.ParsedPublication;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * Statement-shape guards for the repair path (OPN-7, ruled 2026-11-27). The behaviour - one
 * transaction, no new version, a shared node reused rather than doubled - needs a store and is proved
 * by the container IT; what is checked here is what can be read off the statements before they run.
 *
 * <p>Fixtures come from {@link ParsedModelGraphMapper} and small {@link ParsedModel} objects rather than
 * hand-built {@link ExtractedModelGraph}s: the fixtures have to carry shared {@code RESOURCE},
 * {@code PERSON} and {@code PUBLICATION} nodes with their natural keys, and the mapper is the only
 * producer that writes them the way the repair must merge them.
 */
class ModelGraphRepairCypherTest {
    private static final String DOCUMENT_UID = "6f1a6f43-0d1a-4a3e-9d3b-0f9b6a0c1e21";

    @Test
    void repairCreatesNoDocumentAndBumpsNoGlobalCounter() {
        var repair = ParsedModelGraphCypher.prepareRepair(graph(3), DOCUMENT_UID);
        var all = allCypher(repair);

        assertFalse(all.contains("GlobalUniqueId"), "a repair must not bump the legacy uid counter: " + all);
        assertTrue(all.lines().noneMatch(line -> line.contains("CREATE") && line.contains(":Document")),
                "a document-shaped CREATE is what makes the write a new version; the repair has none");
    }

    @Test
    void theDeleteRemovesOnlyWhatTheDocumentOwns() {
        var delete = ParsedModelGraphCypher.prepareRepair(graph(3), DOCUMENT_UID).deleteDerived();
        var cypher = delete.cypher();

        assertTrue(cypher.contains("n.ownerUid = d.uid"), cypher);
        assertTrue(cypher.contains("FOREACH (node IN owned | DETACH DELETE node)"), cypher);
        assertTrue(cypher.contains("RETURN size(owned) AS nodes"), cypher);
        assertFalse(cypher.contains("DETACH DELETE d"),
                "the Document node survives a repair, it is updated rather than recreated: " + cypher);
        assertEquals(Map.of("uid", DOCUMENT_UID), delete.parameters(), "$uid is the delete's only parameter");
        assertEquals(cypher, ModelDeletionCypher.deleteDerivedGraphCypher(),
                "the repair runs the deletion builder of the lifecycle Cypher, not a second spelling of it");
    }

    @Test
    void sharedNodeSetsAreMergedOnTheirNaturalKeyWhileOwnedSetsAreCreated() {
        var repair = ParsedModelGraphCypher.prepareRepair(graph(3), DOCUMENT_UID);
        var all = allCypher(repair);

        assertTrue(all.contains("MERGE (n:RESOURCE {uri: row.key.uri})"), all);
        assertTrue(all.contains("MERGE (n:PERSON {familyName: row.key.familyName, givenName: row.key.givenName})"), all);
        assertTrue(all.contains("MERGE (n:PUBLICATION {pubId: row.key.pubId})"), all);
        assertTrue(all.contains("CREATE (n:SBML_SPECIES)"), "an owned set is still created: " + all);
        assertTrue(all.contains("SET n += row.properties, n.derivedKey = row.nodeKey"),
                "every node the repair writes carries the identity the write path stamps: " + all);
        assertTrue(all.lines().noneMatch(line -> line.contains("CREATE") && line.contains(":RESOURCE")), all);
        assertTrue(all.lines().noneMatch(line -> line.contains("CREATE") && line.contains(":PERSON")), all);
        assertTrue(all.lines().noneMatch(line -> line.contains("CREATE") && line.contains(":PUBLICATION")), all);
    }

    @Test
    void theDocumentUpdateSetsTheExistingNodeAndKeepsItsIdentity() {
        var update = ParsedModelGraphCypher.prepareRepair(graph(3), DOCUMENT_UID).documentUpdate();

        assertTrue(update.cypher().contains("MATCH (d:Document {uid: $documentUid})"), update.cypher());
        assertTrue(update.cypher().contains("SET d += $documentProperties"), update.cypher());
        assertFalse(update.cypher().contains("CREATE"), "the update creates nothing: " + update.cypher());
        assertEquals(DOCUMENT_UID, update.parameters().get("documentUid"));

        var properties = typedMap(update.parameters().get("documentProperties"));
        assertFalse(properties.containsKey("uid"), "the stored uid is not overwritten by the mapper's");
        assertFalse(properties.containsKey("legacyUid"), "the stored legacy uid is the version's identity");
        assertEquals(DOCUMENT_UID, properties.get("ownerUid"));
        assertEquals("example.xml", properties.get("fileId"));
    }

    @Test
    void theOrphanPurgeFollowsTheWriteAndSkipsUnits() {
        var purges = ParsedModelGraphCypher.repairPurgeStatements();

        assertEquals(3, purges.size(), "one purge per shared annotation label");
        for (var label : List.of("RESOURCE", "PUBLICATION", "PERSON")) {
            assertTrue(
                    purges.stream().anyMatch(statement -> statement.cypher().equals(
                            "MATCH (n:" + label + ") " + ModelDeletionCypher.orphanPurgeBody(label))),
                    "the purge reuses the delete path's own predicate for " + label);
        }
        assertTrue(purges.stream().noneMatch(statement -> statement.cypher().contains("UNIT")),
                "the unit layer has its own backfill; a repair must not purge units");
    }

    /**
     * STO-7 applied to the repair: however many elements the document has, no statement's text may
     * grow with it. Up to one batch the block count is a schema fact too; past {@code BATCH_SIZE} it
     * grows by chunks, which is the batching working, so the count is compared below the batch size and
     * the statement text above it.
     */
    @Test
    void statementCountAndLongestStatementDoNotGrowWithTheDocument() {
        var small = statements(ParsedModelGraphCypher.prepareRepair(graph(3), DOCUMENT_UID));
        var large = statements(ParsedModelGraphCypher.prepareRepair(graph(200), DOCUMENT_UID));
        var huge = statements(ParsedModelGraphCypher.prepareRepair(graph(600), DOCUMENT_UID));

        assertEquals(small.size(), large.size(),
                "below one batch the repair's statement count is a schema fact, not a size fact: "
                        + large.size());
        assertEquals(longestStatement(small), longestStatement(huge),
                "a repair statement's text must not depend on how many elements the document has");
        assertTrue(longestStatement(huge) < 1_000,
                "the longest statement stays a fixed template, was " + longestStatement(huge) + " chars");
    }

    @Test
    void refusesAGraphWithoutItsDocumentNodeBeforeAnyStatementExists() {
        var withoutDocument = new ExtractedModelGraph(
                new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                List.of(new ExtractedGraphNode("model", Set.of("Model", "SBML_MODEL"), Map.of("modelId", "m1"))),
                List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> ParsedModelGraphCypher.prepareRepair(withoutDocument, DOCUMENT_UID));
    }

    @Test
    void refusesARepairWithoutTheUidOfAnExistingDocument() {
        assertThrows(IllegalArgumentException.class, () -> ParsedModelGraphCypher.prepareRepair(graph(3), " "));
    }

    /**
     * A repair keeps the insert's own output: one block per label set, the same label chains in the same
     * order, and the same relationship blocks, so the two paths cannot drift into different graphs. A set
     * that touches a shared label differs by design - it is merged on its natural key instead of created -
     * and that difference is asserted rather than tolerated.
     */
    @Test
    void writesTheSameGraphAsTheInsertPath() {
        var graph = graph(3);
        var repair = ParsedModelGraphCypher.prepareRepair(graph, DOCUMENT_UID);
        var insert = ParsedModelGraphCypher.prepare(graph);

        var insertUid = insert.document().parameters().get("documentUid");
        assertFalse(insertUid.equals(DOCUMENT_UID), "the insert mints its own uid");

        var insertBlocks = insert.nodes().stream().map(BatchedGraphWrite.Statement::cypher).toList();
        var repairBlocks = repair.nodes().stream().map(BatchedGraphWrite.Statement::cypher).toList();

        assertEquals(insertBlocks.size(), repairBlocks.size(), "both paths write one block per label set");
        for (var index = 0; index < insertBlocks.size(); index++) {
            var insertBlock = insertBlocks.get(index);
            var repairBlock = repairBlocks.get(index);
            assertEquals(chainOf(insertBlock), chainOf(repairBlock), "the same label sets, in the same order");
            if (isShared(insertBlock)) {
                assertTrue(repairBlock.contains("MERGE (n"), "a shared set merges in the repair: " + repairBlock);
                assertTrue(repairBlock.contains("row.key."), "and it merges on the natural key: " + repairBlock);
            } else {
                assertEquals(insertBlock, repairBlock, "a set no other document can hold is written identically");
            }
        }
        assertEquals(
                insert.relationshipStatements(elementIds(insert.nodes())).size(),
                repair.relationshipStatements(elementIds(repair.nodes())).size(),
                "both paths write the same relationship blocks");
    }

    /** The label chain a node block writes, for example {@code :SBML_SPECIES}. */
    private static String chainOf(String block) {
        var start = block.indexOf("(n");
        if (start < 0) {
            return "";
        }
        var end = block.indexOf(')', start);
        var space = block.indexOf(' ', start);
        var stop = end >= 0 && (space < 0 || end < space) ? end : space;
        return block.substring(start + 2, stop);
    }

    private static boolean isShared(String block) {
        return block.contains(":RESOURCE") || block.contains(":PERSON") || block.contains(":PUBLICATION");
    }

    /** One SBML model carrying {@code speciesCount} species plus one resource, person and publication. */
    private ExtractedModelGraph graph(int speciesCount) {
        var entities = new ArrayList<ParsedModelEntity>(speciesCount);
        for (var index = 0; index < speciesCount; index++) {
            entities.add(new ParsedModelEntity(
                    ParsedModelEntity.EntityType.SPECIES,
                    "s" + index,
                    "Species " + index,
                    Map.of("id", "s" + index, "name", "Species " + index)));
        }
        return new ParsedModelGraphMapper()
                .toGraph(new ParsedModel(
                        new ModelReference("example.xml", URI.create("https://example.org/example.xml"), ModelType.SBML),
                        "m1",
                        "Example model",
                        Map.of(),
                        entities,
                        List.of(),
                        List.of(new ParsedAnnotation(
                                "model",
                                Map.of("id", "ann1"),
                                List.of(new ParsedAnnotationResource("https://identifiers.org/go/GO:0008150", "isDescribedBy")),
                                List.of(new ParsedPerson("Jane", "Doe", "jj@example.org", "Uni")),
                                List.of())),
                        List.of(new ParsedPublication(
                                "pub-1", "Title", "Journal", null, null, "2014",
                                List.of(new ParsedPerson("John", "Roe", "jr@example.org", "Uni"))))));
    }

    /** Every statement a repair emits, in the order it runs them. */
    private List<String> statements(ParsedModelGraphCypher.RepairedGraphWrite repair) {
        var statements = new ArrayList<String>();
        statements.add(repair.deleteDerived().cypher());
        statements.add(repair.documentUpdate().cypher());
        repair.nodes().forEach(statement -> statements.add(statement.cypher()));
        repair.relationshipStatements(elementIds(repair.nodes())).forEach(statement -> statements.add(statement.cypher()));
        ParsedModelGraphCypher.repairPurgeStatements().forEach(statement -> statements.add(statement.cypher()));
        return statements;
    }

    private String allCypher(ParsedModelGraphCypher.RepairedGraphWrite repair) {
        return String.join("\n", statements(repair));
    }

    private int longestStatement(List<String> statements) {
        return statements.stream().mapToInt(String::length).max().orElse(0);
    }

    /** Placeholder element ids for every key the node blocks report, so endpoints resolve. */
    private Map<String, String> elementIds(List<BatchedGraphWrite.Statement> nodes) {
        var ids = new HashMap<String, String>();
        ids.put("document", "document-element-id");
        nodes.forEach(statement -> {
            @SuppressWarnings("unchecked")
            var rows = (List<Map<String, Object>>) statement.parameters().get("rows");
            rows.forEach(row -> ids.put(row.get("nodeKey").toString(), "element-id"));
        });
        return ids;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> typedMap(Object value) {
        return (Map<String, Object>) value;
    }
}
