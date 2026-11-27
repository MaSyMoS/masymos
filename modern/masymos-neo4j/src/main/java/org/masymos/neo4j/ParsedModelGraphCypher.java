package org.masymos.neo4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.masymos.core.extract.ExtractedGraphNode;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.MetadataJson;
import org.masymos.core.query.ExactText;

final class ParsedModelGraphCypher {
    private static final Set<String> DOCUMENT_LABELS = Set.of("Document");
    /**
     * Labels of annotation nodes that a later shared write could attach to several documents. They
     * are deliberately not stamped with {@code ownerUid}: the orphan purge decides their fate, so a
     * delete never removes a node another document still references. {@code UNIT} is shared on its
     * canonical key (CML-1), which is why the write merges it instead of creating it.
     */
    private static final Set<String> SHARED_NODE_LABELS = Set.of("RESOURCE", "PERSON", "PUBLICATION", "UNIT");
    /**
     * How a block writes a node set that touches one of {@link #SHARED_NODE_LABELS} (OPN-7).
     *
     * <p>An insert creates the node: the document is new, so nothing can be there already. A repair
     * runs beside other documents' graphs, so a shared node those still reference must be found and
     * reused rather than written twice - it is merged on its natural key, the one property set that
     * identifies it whatever document names it. {@code UNIT} is shared on {@code key} in both modes
     * (CML-1) and is the one shared label a repair does not purge: the unit layer has its own
     * backfill.
     */
    private enum SharedMode {
        CREATE,
        MERGE
    }
    private static final Set<Set<String>> MODEL_LABEL_SETS = Set.of(            Set.of("Model", "SBML_MODEL"),
            Set.of("Model", "CELLML_MODEL"),
            Set.of("Model", "SEDML_MODEL"),
            Set.of("Model", "OWL_MODEL")
    );
    /**
     * QRY-8: the equality keys the query planners match on. Stamped here for every node, so a shared
     * annotation node gets them too, and both sides of a query use {@link ExactText}.
     */
    private static final Map<String, List<ExactCopy>> EXACT_COPIES = Map.of(
            "Document", List.of(new ExactCopy("sourceUri", "sourceUriExact", ExactText::lowercase)),
            "Model", List.of(
                    new ExactCopy("sourceUri", "sourceUriExact", ExactText::lowercase),
                    new ExactCopy("modelId", "modelIdExact", ExactText::lowercase)),
            "RESOURCE", List.of(new ExactCopy("uri", "uriExact", ExactText::identity)),
            "PERSON", List.of(
                    new ExactCopy("familyName", "familyNameExact", ExactText::lowercase),
                    new ExactCopy("givenName", "givenNameExact", ExactText::lowercase)),
            "PUBLICATION", List.of(
                    new ExactCopy("pubId", "pubIdExact", ExactText::lowercase),
                    new ExactCopy("year", "yearExact", ExactText::lowercase))
    );
    static final Set<String> SUPPORTED_LABELS = Set.of(            "Document",
            "Model",
            "SBML_MODEL",
            "CELLML_MODEL",
            "SEDML_MODEL",
            "OWL_MODEL",
            "SBML_COMPARTMENT",
            "SBML_SPECIES",
            "SBML_REACTION",
            "SBML_PARAMETER",
            "SBML_GENE_PRODUCT",
            "SBML_SUBMODEL",
            "SBML_RULE",
            "SBML_FUNCTION",
            "SBML_EVENT",
            "ANNOTATION",
            "RESOURCE",
            "PERSON",
            "PUBLICATION",
            "CELLML_COMPONENT",
            "CELLML_VARIABLE",
            "CELLML_REACTION",
            "CELLML_IMPORT",
            "CELLML_IMPORTED_COMPONENT",
            "SEDML_MODELREFERENCE",
            "SEDML_SIMULATION",
            "SEDML_TASK",
            "SEDML_DATAGENERATOR",
            "SEDML_VARIABLE",
            "SEDML_OUTPUT",
            "SEDML_CURVE",
            "SEDML_SURFACE",
            "SEDML_DATASET",
            "SEDML_RANGE",
            "SEDML_CHANGE",
            "UNIT"
    );
    static final Set<String> SUPPORTED_RELATIONSHIPS = Set.of(
            "HAS_MODEL",
            "HAS_COMPARTMENT",
            "HAS_SPECIES",
            "HAS_REACTION",
            "HAS_PARAMETER",
            // FBC-2 (decision 52): the model owns its gene products, a reaction references them. Both
            // types are spliced in from a fixed set, so the whitelist is what keeps a typo out of a
            // statement - the same trap that made every CellML import fail once (AGENTS.md).
            "HAS_GENE_PRODUCT",
            "ASSOCIATED_WITH",
            // OPN-2 (ruling 2 of 2026-11-26): `comp` is read, so a composed model owns its submodels and
            // a submodel replaces the entities its ports name. Both types are spliced in from this set
            // like every other one, for the reason the FBC-2 note above records.
            "COMPOSED_FROM",
            "REPLACES",
            "HAS_RULE",
            "HAS_FUNCTION",
            "HAS_EVENT",
            "HAS_COMPONENT",
            "HAS_VARIABLE",
            "IS_CONNECTED_TO",
            "HAS_MODELREFERENCE",
            "HAS_SIMULATION",
            "HAS_TASK",
            "HAS_DATAGENERATOR",
            "HAS_OUTPUT",
            "HAS_CURVE",
            "HAS_SURFACE",
            "HAS_DATASET",
            // A repeated task's own edges. They reached the writer only through the
            // sanitized-qualifier fallback, which `CorpusCoverage` does not read, so the matrix
            // could neither count them nor report their absence (T025-1).
            "HAS_RANGE",
            "HAS_CHANGE",
            "HAS_SUBTASK",
            "HAS_ONTOLOGY_TERM",
            "REFERENCES_MODEL",
            "REFERENCES_SIMULATION",
            "IS_REFERENCED_IN_TASK",
            "SIMULATES",
            "IS_SIMULATED",
            "CALCULATES_MODEL",
            "USED_IN_DATAGENERATOR",
            "REFERENCES_SIMULATION_MODEL",
            "OBSERVES",
            "IS_ENTITY_OF",
            "BELONGS_TO",
            "IS_LOCATED_IN",
            "CONTAINS_SPECIES",
            "CONTAINS_REACTION",
            "HAS_REACTANT",
            "HAS_PRODUCT",
            "HAS_MODIFIER",
            "IS_REACTANT",
            "IS_PRODUCT",
            "IS_MODIFIER",
            "HAS_DELTA_VAR",
            "IS_DELTA_VAR",
            "HAS_ANNOTATION",
            "HAS_SBOTERM",
            "HAS_KISAO",
            "IS_CREATOR",
            "HAS_PUBLICATION",
            "HAS_AUTHOR",
            "HAS_RESOURCE",
            "COMPOSED_OF",
            "HAS_UNIT",
            "USES_UNIT",
            "HAS_IMPORT",
            "HAS_IMPORTED_COMPONENT"
    );

    private ParsedModelGraphCypher() {
    }

    /**
     * Writes the normalized copy for every identity property of the node's labels. An absent, null,
     * non-String or blank source writes no copy at all: an empty exact value would be a value no
     * document can equal, and legacy never indexed one either.
     */
    private static void addExactCopies(Set<String> labels, Map<String, Object> properties) {
        labels.stream()
                .flatMap(label -> EXACT_COPIES.getOrDefault(label, List.of()).stream())
                .forEach(copy -> {
                    if (properties.get(copy.source()) instanceof String value && !value.isBlank()) {
                        properties.put(copy.target(), copy.normalize().apply(value));
                    }
                });
    }

    static BatchedGraphWrite prepare(ExtractedModelGraph graph) {
        requireNode(graph, "document", DOCUMENT_LABELS);
        requireModelNode(graph);
        validateGraph(graph);

        // Generated here rather than by the database, because every node of this document has to
        // carry it as ownerUid: that is what makes delete ownership-scoped (STO-4).
        var documentUid = UUID.randomUUID().toString();
        return new BatchedGraphWrite(
                documentStatement(graph, documentUid),
                nodeStatements(graph, documentUid, SharedMode.CREATE),
                relationshipBatches(graph));
    }

    /**
     * The write half of a repair (OPN-7, ruled 2026-11-27): everything needed to replace the derived
     * graph of the document {@code documentUid}, in one transaction, without writing a new version.
     *
     * <p>What differs from {@link #prepare(ExtractedModelGraph)} is only the document. The new graph's
     * nodes and relationships are the write path's own, under the same {@code ownerUid}, so a repaired
     * document is what a re-import would have produced - except that the document node is not created
     * and the global counter is not bumped: its properties are {@code SET} onto the existing node,
     * matched by {@code uid}, so {@code uid}, {@code legacyUid}, {@code versionId} and the chain
     * position survive. Shared nodes are merged on their natural key, because another document's graph
     * is in the same transaction's store. The delete comes first so the old generation cannot survive
     * beside the new one.
     *
     * <p>The returned graph keys address the nodes written here; the document keeps its own
     * {@code document} key, which the caller resolves to the element id the update reports.
     */
    static RepairedGraphWrite prepareRepair(ExtractedModelGraph graph, String documentUid) {
        if (documentUid == null || documentUid.isBlank()) {
            throw new IllegalArgumentException("a repair needs the uid of an existing document");
        }
        requireNode(graph, "document", DOCUMENT_LABELS);
        requireModelNode(graph);
        validateGraph(graph);

        return new RepairedGraphWrite(
                new BatchedGraphWrite.Statement(ModelDeletionCypher.deleteDerivedGraphCypher(), Map.of("uid", documentUid)),
                documentUpdateStatement(graph, documentUid),
                nodeStatements(graph, documentUid, SharedMode.MERGE),
                relationshipBatches(graph));
    }

    /**
     * The orphan purges a repair runs after its write, one per shared label that a delete also purges
     * (STO-4). {@code UNIT} is deliberately absent: the unit layer has its own backfill (CML-1).
     */
    static List<BatchedGraphWrite.Statement> repairPurgeStatements() {
        return ModelDeletionCypher.ORPHAN_LABELS.stream()
                .map(label -> new BatchedGraphWrite.Statement(
                        "MATCH (n:" + label + ") " + ModelDeletionCypher.orphanPurgeBody(label),
                        Map.of()))
                .toList();
    }

    /**
     * The prologue of the write: one bump of the global counter and the document node, whose
     * {@code uid} and {@code legacyUid} have to be minted together.
     *
     * <p>One global counter, exactly as legacy mints its numeric uid (IdFactory.java:24): the first
     * document gets 1, values are never reused. It is kept so a client holding a legacy numeric uid can
     * still resolve a document (decision D1).
     *
     * <p>It is its own statement because the counter's value is read as {@code legacyUid} here and
     * nowhere else, and because the node's properties carry the stored source document (STO-2), which
     * is the largest single parameter of the write. The statement also returns the document's element
     * id, so the relationship blocks can address it like every other node.
     */
    private static BatchedGraphWrite.Statement documentStatement(ExtractedModelGraph graph, String documentUid) {
        var document = graph.nodes().stream()
                .filter(node -> node.key().equals("document"))
                .findFirst()
                .orElseThrow();
        var documentProperties = new LinkedHashMap<String, Object>(document.properties());
        addExactCopies(document.labels(), documentProperties);
        documentProperties.put("ownerUid", documentUid);
        addStoredDocument(documentProperties, graph);
        return new BatchedGraphWrite.Statement("""
                MERGE (id:GlobalUniqueId)
                ON CREATE SET id.count = 1
                ON MATCH SET id.count = id.count + 1
                WITH id.count AS legacyUid
                CREATE (n0:Document {uid: $documentUid, legacyUid: legacyUid})
                SET n0 += $documentProperties
                RETURN n0.uid AS uid, n0.fileId AS fileId, n0.versionId AS versionId,
                       n0.legacyUid AS legacyUid, elementId(n0) AS eid
                """, Map.of(
                "documentUid", documentUid,
                "documentProperties", documentProperties));
    }

    /**
     * The document half of a repair (OPN-7): the freshly mapped properties onto the <em>existing</em>
     * {@code Document}, matched by {@code uid}. No counter is bumped and no node is created - {@code
     * uid}, {@code legacyUid}, {@code versionId} and the chain position are what makes a repair not a
     * new version, so none of them is written here even though the mapper would supply some of them.
     *
     * <p>The statement still returns the identifiers a write reports, and the element id, because the
     * relationship blocks address the document by element id like every other node. A missing document
     * is an empty result, never a silent no-op: the caller fails the transaction on it.
     */
    private static BatchedGraphWrite.Statement documentUpdateStatement(ExtractedModelGraph graph, String documentUid) {
        var document = graph.nodes().stream()
                .filter(node -> node.key().equals("document"))
                .findFirst()
                .orElseThrow();
        var documentProperties = new LinkedHashMap<String, Object>(document.properties());
        addExactCopies(document.labels(), documentProperties);
        // The mapper's own properties stop here: identity and ownership are the stored document's.
        documentProperties.remove("uid");
        documentProperties.remove("legacyUid");
        documentProperties.remove("ownerUid");
        documentProperties.put("ownerUid", documentUid);
        addStoredDocument(documentProperties, graph);
        return new BatchedGraphWrite.Statement("""
                MATCH (d:Document {uid: $documentUid})
                SET d += $documentProperties
                RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId,
                       d.legacyUid AS legacyUid, elementId(d) AS eid
                """, Map.of(
                "documentUid", documentUid,
                "documentProperties", documentProperties));
    }

    /**
     * One {@code UNWIND} block per label set the document uses, because a label cannot be a parameter.
     * The label sets are the writer's vocabulary, so the number of blocks is bounded by {@link
     * #SUPPORTED_LABELS} and never by the document: only the rows inside a block grow, and those are
     * cut into {@link BatchedGraphWrite#BATCH_SIZE} at a time.
     *
     * <p>{@code sharedMode} is the only difference between an insert and a repair (OPN-7): it decides
     * whether a node set touching one of {@link #SHARED_NODE_LABELS} is created or merged on its
     * natural key. The blocks themselves stay one set of builders, so the two paths cannot drift.
     */
    private static List<BatchedGraphWrite.Statement> nodeStatements(
            ExtractedModelGraph graph, String documentUid, SharedMode sharedMode) {
        var rowsByLabels = new LinkedHashMap<Set<String>, List<Map<String, Object>>>();
        var unmergeable = new java.util.HashSet<Set<String>>();
        for (var node : graph.nodes()) {
            if (node.key().equals("document")) {
                continue;
            }
            var properties = new LinkedHashMap<String, Object>(node.properties());
            addExactCopies(node.labels(), properties);
            // Annotation nodes another document could share are not owned: a delete must leave them
            // to the orphan purge, which keeps them while anything still references them (STO-4).
            if (node.labels().stream().noneMatch(SHARED_NODE_LABELS::contains)) {
                properties.put("ownerUid", documentUid);
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("nodeKey", node.key());
            row.put("properties", properties);
            if (node.labels().contains("UNIT")) {
                // Units are shared nodes (CML-1): one node per canonical key, whatever model names it
                // and however that model spelled the unit. The key is the merge key of the block.
                row.put("unitKey", node.properties().get("key"));
            } else if (sharedMode == SharedMode.MERGE && touchesSharedLabel(node.labels())) {
                var key = naturalKey(node.labels(), properties);
                if (key.isEmpty()) {
                    // Anonymous shared node: nothing to merge on, so its whole set is written by CREATE.
                    unmergeable.add(node.labels());
                } else {
                    row.put("key", key);
                }
            }
            rowsByLabels.computeIfAbsent(node.labels(), labels -> new ArrayList<>()).add(row);
        }
        var statements = new ArrayList<BatchedGraphWrite.Statement>();
        rowsByLabels.forEach((labels, rows) -> {
            var cypher = labels.contains("UNIT")
                    ? """
                    UNWIND $rows AS row
                    MERGE (n:UNIT {key: row.unitKey})
                    SET n += row.properties
                    RETURN row.nodeKey AS nodeKey, elementId(n) AS eid
                    """
                    : sharedMode == SharedMode.MERGE && touchesSharedLabel(labels) && !unmergeable.contains(labels)
                            ? mergeOnNaturalKeyCypher(labels)
                            : """
                            UNWIND $rows AS row
                            CREATE (n%s)
                            SET n += row.properties, n.derivedKey = row.nodeKey
                            RETURN row.nodeKey AS nodeKey, elementId(n) AS eid
                            """.formatted(labelChain(labels));
            for (var chunk : BatchedGraphWrite.chunks(rows)) {
                statements.add(new BatchedGraphWrite.Statement(cypher, Map.of("rows", chunk)));
            }
        });
        return List.copyOf(statements);
    }

    /**
     * The repair block of a shared node set: merged on its natural key so a node another document still
     * references is reused, not written twice (OPN-7). The keys are passed as {@code row.key.<name>}
     * because a map literal cannot be a parameter either.
     *
     * <p>A missing key is an error rather than a silent second copy, which is what an unequal
     * {@code MERGE} pattern would produce. {@code derivedKey} is set here too: the write path stamps it
     * on every node it writes, shared or owned.
     */
    private static String mergeOnNaturalKeyCypher(Set<String> labels) {
        return """
                UNWIND $rows AS row
                MERGE (n%s%s)
                SET n += row.properties, n.derivedKey = row.nodeKey
                RETURN row.nodeKey AS nodeKey, elementId(n) AS eid
                """.formatted(labelChain(labels), naturalKeyPattern(labels));
    }

    /** The natural key of a shared label set, as the {@code MERGE} pattern it is matched on. */
    private static String naturalKeyPattern(Set<String> labels) {
        var names = naturalKeyNames(labels);
        if (names.isEmpty()) {
            throw new IllegalArgumentException("shared labels without a natural key: " + labels);
        }
        return " {" + names.stream()
                .map(name -> name + ": row.key." + name)
                .collect(Collectors.joining(", ")) + "}";
    }

    /**
     * The properties that identify a shared node whatever document names it - the one place the repair
     * is stricter than an insert: {@code RESOURCE} on {@code uri}, {@code PUBLICATION} on {@code pubId},
     * {@code PERSON} on {@code familyName} + {@code givenName}. Read from the row's properties, which
     * already carry the normalized copies, so the merge matches what the insert would have written.
     */
    private static List<String> naturalKeyNames(Set<String> labels) {
        var names = new ArrayList<String>();
        if (labels.contains("RESOURCE")) {
            names.add("uri");
        }
        if (labels.contains("PUBLICATION")) {
            names.add("pubId");
        }
        if (labels.contains("PERSON")) {
            names.add("familyName");
            names.add("givenName");
        }
        return List.copyOf(names);
    }

    /**
     * A shared node without its natural key cannot be merged and must not be: it is anonymous, so there
     * is nothing to match on, and an empty {@code MERGE} key would match a differently-keyed node or
     * none. Such a set falls back to {@code CREATE} - what an insert does for it - so a repair of a
     * document whose annotation has no {@code uri} still writes, instead of aborting the page. The
     * container test that found this is
     * {@code Neo4jContainersIT#reusesASharedAnnotationNodeAnotherDocumentStillReferences}.
     */
    private static Map<String, Object> naturalKey(Set<String> labels, Map<String, Object> properties) {
        var key = new LinkedHashMap<String, Object>();
        for (var name : naturalKeyNames(labels)) {
            var value = properties.get(name);
            if (!(value instanceof String text) || text.isBlank()) {
                return Map.of();
            }
            key.put(name, value);
        }
        return key;
    }

    private static boolean touchesSharedLabel(Set<String> labels) {
        return labels.stream().anyMatch(SHARED_NODE_LABELS::contains);
    }

    /**
     * Relationships grouped by type, because a type cannot be a parameter either. The endpoints stay
     * named by graph key here; {@link BatchedGraphWrite#relationshipStatements} resolves them to the
     * element ids the node blocks reported.
     */
    private static List<BatchedGraphWrite.RelationshipBatch> relationshipBatches(ExtractedModelGraph graph) {
        var rowsByType = new LinkedHashMap<String, List<BatchedGraphWrite.Row>>();
        for (var relationship : graph.relationships()) {
            rowsByType
                    .computeIfAbsent(relationship.type(), type -> new ArrayList<>())
                    .add(new BatchedGraphWrite.Row(
                            relationship.startKey(),
                            relationship.endKey(),
                            new LinkedHashMap<>(relationship.properties())));
        }
        return rowsByType.entrySet().stream()
                .map(entry -> new BatchedGraphWrite.RelationshipBatch(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    /**
     * The document node carries the source document (legacy {@code XMLDOC}, STO-2) and the caller's
     * metadata (legacy {@code META}). Nothing is written when neither exists, so documents indexed
     * before STO-2 keep exactly the properties they had.
     */
    private static void addStoredDocument(Map<String, Object> properties, ExtractedModelGraph graph) {
        var sourceDocument = graph.sourceDocument();
        if (sourceDocument != null) {
            properties.put("xmlDoc", sourceDocument.content());
            properties.put("xmlDocEncoding", sourceDocument.encoding());
            properties.put("xmlDocBytes", sourceDocument.originalBytes());
            properties.put("xmlDocTruncated", sourceDocument.truncated());
        }
        var metadata = MetadataJson.write(graph.reference().metadata());
        if (metadata != null) {
            properties.put("meta", metadata);
        }
    }

    private static void requireNode(ExtractedModelGraph graph, String key, Set<String> labels) {        graph.nodes().stream()
                .filter(node -> node.key().equals(key))
                .filter(node -> node.labels().equals(labels))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Extracted graph does not contain supported node " + key + " with labels " + labels));
    }

    private static void requireModelNode(ExtractedModelGraph graph) {
        graph.nodes().stream()
                .filter(node -> node.key().equals("model"))
                .filter(node -> MODEL_LABEL_SETS.contains(node.labels()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Extracted graph does not contain supported typed model node"));
    }

    private static void validateGraph(ExtractedModelGraph graph) {
        var nodeKeys = graph.nodes().stream().map(ExtractedGraphNode::key).collect(Collectors.toSet());
        graph.nodes().forEach(node -> {
            if (!hasSupportedLabels(node)) {
                throw new IllegalArgumentException("Extracted graph contains unsupported labels " + node.labels());
            }
        });
        graph.relationships().forEach(relationship -> {
            if (!SUPPORTED_RELATIONSHIPS.contains(relationship.type()) && !isSanitizedQualifier(relationship.type())) {
                throw new IllegalArgumentException("Extracted graph contains unsupported relationship " + relationship.type());
            }
            if (!nodeKeys.contains(relationship.startKey()) || !nodeKeys.contains(relationship.endKey())) {
                throw new IllegalArgumentException("Extracted graph contains relationship with unknown endpoint");
            }
        });
    }

    private static String labelChain(Set<String> labels) {
        return labels.stream().sorted().map(label -> ":" + label).collect(Collectors.joining());
    }

    /**
     * A relationship type is spliced into a statement, because Cypher takes a type as syntax rather
     * than as a parameter ({@code BatchedGraphWrite.relationshipCypher}). That statement quotes the type,
     * so a word that is a Cypher keyword is safe there - the type stays a type and cannot close the
     * clause ({@code bqbiol:is} writes as {@code `is`}, which is what the corpus needs). What is left
     * for this guard is the shape: the type must be a plain identifier, so nothing can break out of the
     * quotes - no backtick, no whitespace, no comment or clause text (DB-001).
     */
    private static boolean isSanitizedQualifier(String type) {
        return type.matches("[A-Za-z][A-Za-z0-9_]*");
    }

    private static boolean hasSupportedLabels(ExtractedGraphNode node) {
        return node.labels().stream().allMatch(label -> SUPPORTED_LABELS.contains(label) || isOntologyLabel(label));
    }

    private static boolean isOntologyLabel(String label) {
        return label.matches("[A-Za-z][A-Za-z0-9_]*Ontology");
    }

    /** One row of {@link #EXACT_COPIES}: copy {@code source} to {@code target} through {@code normalize}. */
    private record ExactCopy(String source, String target, UnaryOperator<String> normalize) {
    }

    /**
     * One repair of a document's derived graph (OPN-7, {@code prepareRepair}): the delete of what the
     * document owns, the update of its existing node, the write path's own node blocks and relationship
     * batches under the same {@code ownerUid}, and - from {@link #repairPurgeStatements()} - the orphan
     * purges that run after them. Every one of them runs in the caller's single transaction, in this
     * order, and none of them mints a version.
     *
     * <p>The records are deliberately separate from {@link BatchedGraphWrite}: a repair has no prologue
     * that creates a document and no counter bump, and typing it as a write would let a caller run the
     * insert's prologue against a document it must not touch.
     */
    record RepairedGraphWrite(
            BatchedGraphWrite.Statement deleteDerived,
            BatchedGraphWrite.Statement documentUpdate,
            List<BatchedGraphWrite.Statement> nodes,
            List<BatchedGraphWrite.RelationshipBatch> relationships
    ) {
        /** The relationship statements, with every endpoint key resolved to a written element id. */
        List<BatchedGraphWrite.Statement> relationshipStatements(Map<String, String> elementIdsByKey) {
            return new BatchedGraphWrite(null, List.of(), relationships).relationshipStatements(elementIdsByKey);
        }
    }
}
