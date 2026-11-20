package org.masymos.neo4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.masymos.core.extract.MetadataJson;
import org.masymos.core.model.ModelConflictException;
import org.masymos.core.model.ModelDeletionResult;
import org.masymos.core.model.ModelIdentifier;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.model.ModelVersion;
import org.masymos.core.model.StoredDocument;
import org.masymos.core.query.ExactMatch;
import org.masymos.core.query.ExactText;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.ModelSearchResult;
import org.masymos.core.query.ModelVersionHit;
import org.masymos.core.query.QueryField;
import org.masymos.core.query.QueryPlan;
import org.masymos.core.spi.ModelRepository;
import org.masymos.core.units.UnitCatalogue;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Values;

public final class Neo4jModelRepository implements ModelRepository {
    private static final String AND = " AND ";
    private static final String OR = " OR ";

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final Neo4jUnitFilterResolver unitFilter;

    public Neo4jModelRepository(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
        this.unitFilter = new Neo4jUnitFilterResolver(driver, sessionConfig, UnitCatalogue.builtIns());
    }

    @Override
    public ModelIdentifier insert(ModelReference modelReference) {
        return insert(modelReference, false);
    }

    @Override
    public ModelIdentifier insert(ModelReference modelReference, boolean enforceUniqueFileId) {
        var write = insertCypher(modelReference.modelType());
        var parameters = new HashMap<String, Object>();
        parameters.put("fileId", modelReference.fileId());
        parameters.put("sourceUri", modelReference.sourceUri().toString());
        // QRY-8: the equality copy of the identity property, normalized by the shared rule.
        parameters.put("sourceUriExact", ExactText.lowercase(modelReference.sourceUri().toString()));
        parameters.put("modelType", modelReference.modelType().name());
        // A null label is allowed: `SET d.versionId = null` removes the property.
        parameters.put("versionId", modelReference.versionId());
        // Same for metadata: an absent map removes the property instead of storing an empty string.
        parameters.put("meta", MetadataJson.write(modelReference.metadata()));
        try (var session = driver.session(sessionConfig)) {
            return session.executeWrite(tx -> {
                if (enforceUniqueFileId) {
                    // The lock makes the check-then-create atomic: the Document.fileId index is not
                    // unique (versions share a fileId), so without it two concurrent requests both
                    // pass the check.
                    tx.run(fileIdLockCypher(), Map.of("fileId", modelReference.fileId())).consume();
                    if (fileExists(tx, modelReference.fileId())) {
                        throw ModelConflictException.duplicateFileId(modelReference.fileId());
                    }
                }
                var record = tx.run(write, parameters).single();
                var uid = record.get("uid").asString();
                var fileId = record.get("fileId").asString();
                tx.run(ModelVersionChainCypher.linkPredecessorsCypher(), Map.of("uid", uid, "fileId", fileId))
                        .consume();
                return new ModelIdentifier(
                        uid,
                        fileId,
                        record.get("versionId").isNull() ? null : record.get("versionId").asString(),
                        record.get("legacyUid").asLong()
                );
            });
        }
    }

    @Override
    public Optional<ModelIdentifier> findByUid(String uid) {
        var parameters = new HashMap<String, Object>();
        parameters.put("uid", uid);
        // A null legacyUid keeps the parameter present: the query guards on `$legacyUid IS NOT NULL`,
        // and Map.copyOf would reject the null value outright.
        parameters.put("legacyUid", legacyUid(uid));
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> tx.run(findByUidCypher(), parameters)
                    .list(Neo4jModelRepository::identifierFrom)
                    .stream()
                    .findFirst());
        }
    }

    @Override
    public Optional<ModelVersion> findLatestVersion(String fileId) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> tx.run(findLatestVersionCypher(), Map.of("fileId", fileId))
                    .list(Neo4jModelRepository::versionFrom)
                    .stream()
                    .findFirst());
        }
    }

    @Override
    public List<ModelVersion> findVersions(String fileId) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> List.copyOf(
                    tx.run(findVersionsCypher(), Map.of("fileId", fileId))
                            .list(Neo4jModelRepository::versionFrom)));
        }
    }

    @Override
    public Optional<ModelVersion> findVersion(String fileId, String versionId) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> tx
                    .run(findVersionCypher(), Map.of("fileId", fileId, "versionId", versionId))
                    .list(Neo4jModelRepository::versionFrom)
                    .stream()
                    .findFirst());
        }
    }

    @Override
    public Optional<StoredDocument> findDocument(String uid) {
        var parameters = new HashMap<String, Object>();
        parameters.put("uid", uid);
        // Null keeps the parameter present for the `$legacyUid IS NOT NULL` guard; Map.copyOf
        // rejects null values, so this map must stay a HashMap (same reason as findByUid).
        parameters.put("legacyUid", legacyUid(uid));
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> tx.run(findDocumentCypher(), parameters)
                    .list(Neo4jModelRepository::storedDocumentFrom)
                    .stream()
                    .findFirst());
        }
    }

    @Override
    public List<ModelSearchResult> queryModels(ModelQueryRequest request) {
        // Units are resolved before the plan: an unknown unit is the caller's error even when the
        // text part alone would have returned nothing.
        var unitKeys = unitFilter.resolve(request.units());
        var plan = Neo4jModelFulltextQuery.plan(request);
        if (plan.isEmpty()) {
            return List.of();
        }
        var cypher = cypherFor(plan, request.groupByVersion(), request.modelTypes(), unitKeys);
        // Both values describe the request, not the hit, so every row of the run shares them.
        var matchedFields = matchedFields(plan, request);
        var indexSource = indexSource(plan.hasText());
        var parameters = new HashMap<String, Object>();
        if (plan.hasText()) {
            parameters.put("keyword", plan.textQuery());
        }
        putExactParameters(parameters, plan.exactMatches());
        parameters.putAll(unitParameters(unitKeys));
        parameters.put("top", request.top());
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> tx.run(cypher, parameters).list(record -> new ModelSearchResult(
                    record.get("uid").asString(),
                    record.get("modelId").asString(),
                    record.get("modelName").asString(),
                    URI.create(record.get("documentUri").asString()),
                    record.get("filename").asString(),
                    record.get("score").asDouble(),
                    // The flat builders select no `versions` column; only a grouped run carries them.
                    request.groupByVersion() ? versionHits(record) : List.of(),
                    record.get("versionId").asString(null),
                    matchedFields,
                    indexSource,
                    record.get("documentStored").asBoolean(false),
                    record.get("documentBytes").isNull() ? null : record.get("documentBytes").asLong(),
                    record.get("documentTruncated").asBoolean(false))));
        }
    }

    /**
     * The request fields that produced the hit, in request order: the plan's clause fields, then
     * {@link QueryField#KEYWORD} when the free-text keyword was used. Duplicates collapse; nothing is
     * invented, so an exact-only request on a field with no clause is not reported.
     */
    static List<QueryField> matchedFields(QueryPlan plan, ModelQueryRequest request) {
        var fields = new LinkedHashSet<QueryField>();
        request.clauses().forEach(clause -> fields.add(clause.field()));
        if (request.keyword() != null && !request.keyword().isBlank()) {
            fields.add(QueryField.KEYWORD);
        }
        return List.copyOf(fields);
    }

    /** {@code modelFulltextV5} for a full-text hit, {@code exact} for an exact-only hit. */
    static String indexSource(boolean hasText) {
        return hasText ? "modelFulltextV5" : "exact";
    }

    /** Grouped requests aggregate per {@code fileId}; ungrouped ones keep today's row per hit. */
    private static String cypherFor(QueryPlan plan, boolean groupByVersion, Collection<ModelType> modelTypes, List<List<String>> unitKeys) {
        if (!groupByVersion) {
            return plan.hasText()
                    ? queryModelsCypher(plan.exactMatches(), modelTypes, unitKeys)
                    : queryModelsExactCypher(plan.exactMatches(), modelTypes, unitKeys);
        }
        return plan.hasText()
                ? groupedModelsCypher(plan.exactMatches(), modelTypes, unitKeys)
                : groupedModelsExactCypher(plan.exactMatches(), modelTypes, unitKeys);
    }

    /**
     * The group's versions, highest score first, then by {@code versionId} ascending with an absent
     * version label last, so equal scores still order deterministically (see the QRY-1 design).
     */
    private static List<ModelVersionHit> versionHits(Record record) {
        var hits = new ArrayList<ModelVersionHit>();
        for (var version : record.get("versions").asList(value -> value.asMap())) {
            // The collected map holds plain Java values; `asString(null)` keeps an absent label null.
            var versionId = Values.value(version.get("versionId")).asString(null);
            hits.add(new ModelVersionHit(
                    Values.value(version.get("uid")).asString(),
                    versionId,
                    Values.value(version.get("score")).asDouble()));
        }
        hits.sort(Comparator.comparingDouble(ModelVersionHit::score)
                .reversed()
                .thenComparing(ModelVersionHit::versionId, Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(hits);
    }

    /**
     * Locks one {@code fileId} for the rest of the transaction, so the uniqueness guard cannot be
     * overtaken by a concurrent insert of the same file.
     */
    static String fileIdLockCypher() {
        return "MERGE (lock:FileIdLock {fileId: $fileId})";
    }

    /**
     * The metadata-only insert. The kind label is a Cypher literal, so the statement is built per
     * kind and the node carries the same label a parsed graph does; {@code P2M} has no label, so it
     * keeps plain {@code :Document:Model} and stays unfilterable. The {@code modelType} property
     * stays either way: the label is the only filter signal, the property is a document fact.
     */
    static String insertCypher(ModelType modelType) {
        return """
                MERGE (id:GlobalUniqueId)
                ON CREATE SET id.count = 1
                ON MATCH SET id.count = id.count + 1
                WITH id.count AS legacyUid
                CREATE (d:Document:Model%s {
                  uid: randomUUID(),
                  legacyUid: legacyUid,
                  fileId: $fileId,
                  sourceUri: $sourceUri,
                  modelType: $modelType
                })
                SET d.versionId = $versionId
                SET d.ownerUid = d.uid
                SET d.sourceUriExact = $sourceUriExact
                SET d.meta = $meta
                RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId, d.legacyUid AS legacyUid
                """.formatted(kindLabel(modelType));
    }

    /** {@code :SBML_MODEL} and its siblings, or an empty string for a kind with no label. */
    static String kindLabel(ModelType modelType) {
        if (modelType == null) {
            return "";
        }
        return switch (modelType) {
            case SBML -> ":SBML_MODEL";
            case CELLML -> ":CELLML_MODEL";
            case SEDML -> ":SEDML_MODEL";
            case OWL -> ":OWL_MODEL";
            // A P2M reference is a mapping file, not a model kind: legacy stamps no label for it.
            case P2M -> "";
        };
    }

    static String findByUidCypher() {
        return """
                MATCH (d:Document)
                WHERE d.uid = $uid OR ($legacyUid IS NOT NULL AND d.legacyUid = $legacyUid)
                RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId, d.legacyUid AS legacyUid
                LIMIT 1
                """;
    }

    static String findLatestVersionCypher() {
        return """
                MATCH (d:Document {fileId: $fileId})
                WHERE NOT (d)-[:HAS_SUCCESSOR]->()
                RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId, d.legacyUid AS legacyUid
                ORDER BY coalesce(d.legacyUid, 0) DESC
                LIMIT 1
                """;
    }

    static String findVersionsCypher() {
        return """
                MATCH (d:Document {fileId: $fileId})
                RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId, d.legacyUid AS legacyUid
                ORDER BY coalesce(d.legacyUid, 0) ASC
                """;
    }

    static String findDocumentCypher() {
        return """
                MATCH (d:Document)
                WHERE d.uid = $uid OR ($legacyUid IS NOT NULL AND d.legacyUid = $legacyUid)
                RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId, d.legacyUid AS legacyUid,
                       d.xmlDoc AS xmlDoc, d.xmlDocEncoding AS xmlDocEncoding, d.xmlDocBytes AS xmlDocBytes,
                       d.xmlDocTruncated AS xmlDocTruncated, d.meta AS meta
                LIMIT 1
                """;
    }

    static String findVersionCypher() {
        return """
                MATCH (d:Document {fileId: $fileId, versionId: $versionId})
                RETURN d.uid AS uid, d.fileId AS fileId, d.versionId AS versionId, d.legacyUid AS legacyUid
                ORDER BY coalesce(d.legacyUid, 0) DESC
                LIMIT 1
                """;
    }

    static String queryModelsCypher() {
        return queryModelsCypher(List.of());
    }

    /** The full-text query without a kind filter, exactly as it was before the filter existed. */
    static String queryModelsCypher(List<ExactMatch> exactMatches) {
        return queryModelsCypher(exactMatches, List.of());
    }

    /** The full-text query with the plan's equality predicates ANDed onto the label check. */
    static String queryModelsCypher(List<ExactMatch> exactMatches, Collection<ModelType> modelTypes) {
        return queryModelsCypher(exactMatches, modelTypes, List.of());
    }

    static String queryModelsCypher(
            List<ExactMatch> exactMatches, Collection<ModelType> modelTypes, List<List<String>> unitKeys) {
        var where = new StringBuilder("WHERE node:Model").append(andKindPredicate(modelTypes));
        if (!exactMatches.isEmpty()) {
            where.append(" AND ").append(exactMatchPredicates(exactMatches));
        }
        where.append(unitPredicate(unitKeys));
        return """
                CALL db.index.fulltext.queryNodes('modelFulltextV5', $keyword)
                YIELD node, score
                %s
                %s
                ORDER BY score DESC
                LIMIT $top
                """.formatted(where, modelProjection("score"));
    }

    /**
     * The exact-only query: no full-text call, because an equality match has no index score. It keeps
     * the same projection as the full-text query, returns the documented constant score {@code 1.0}
     * and orders by the document file id — the typed {@code Model} node has no {@code uid}.
     */
    static String queryModelsExactCypher(List<ExactMatch> exactMatches) {
        return queryModelsExactCypher(exactMatches, List.of());
    }

    static String queryModelsExactCypher(List<ExactMatch> exactMatches, Collection<ModelType> modelTypes) {
        return queryModelsExactCypher(exactMatches, modelTypes, List.of());
    }

    static String queryModelsExactCypher(
            List<ExactMatch> exactMatches, Collection<ModelType> modelTypes, List<List<String>> unitKeys) {
        var where = new StringBuilder(
                exactMatches.isEmpty() ? "WHERE node:Model" : "WHERE " + exactMatchPredicates(exactMatches));
        where.append(andKindPredicate(modelTypes)).append(unitPredicate(unitKeys));
        return """
                MATCH (node:Model)
                %s
                %s
                ORDER BY coalesce(document.fileId, node.fileId), node.modelId
                LIMIT $top
                """.formatted(where, modelProjection("1.0"));
    }

    /**
     * The grouped full-text query: the same hit set as {@link #queryModelsCypher(List)}, aggregated per
     * {@code fileId} before the limit, so {@code $top} counts groups. The group score is the highest
     * member score; the representative row is that best-scoring version (see the QRY-1 design).
     */
    static String groupedModelsCypher(List<ExactMatch> exactMatches) {
        return groupedModelsCypher(exactMatches, List.of());
    }

    static String groupedModelsCypher(List<ExactMatch> exactMatches, Collection<ModelType> modelTypes) {
        return groupedModelsCypher(exactMatches, modelTypes, List.of());
    }

    static String groupedModelsCypher(
            List<ExactMatch> exactMatches, Collection<ModelType> modelTypes, List<List<String>> unitKeys) {
        var where = new StringBuilder("WHERE node:Model").append(andKindPredicate(modelTypes));
        if (!exactMatches.isEmpty()) {
            where.append(" AND ").append(exactMatchPredicates(exactMatches));
        }
        where.append(unitPredicate(unitKeys));
        return """
                CALL db.index.fulltext.queryNodes('modelFulltextV5', $keyword)
                YIELD node, score
                %s
                %s
                ORDER BY score DESC
                LIMIT $top
                """.formatted(where, groupedModelProjection("score"));
    }

    /**
     * The grouped exact-only query: no full-text call, every collected row carries the documented
     * constant score {@code 1.0}, and the groups keep the identity order as their deterministic
     * tie-break.
     */
    static String groupedModelsExactCypher(List<ExactMatch> exactMatches) {
        return groupedModelsExactCypher(exactMatches, List.of());
    }

    static String groupedModelsExactCypher(List<ExactMatch> exactMatches, Collection<ModelType> modelTypes) {
        return groupedModelsExactCypher(exactMatches, modelTypes, List.of());
    }

    static String groupedModelsExactCypher(
            List<ExactMatch> exactMatches, Collection<ModelType> modelTypes, List<List<String>> unitKeys) {
        var where = new StringBuilder(
                exactMatches.isEmpty() ? "WHERE node:Model" : "WHERE " + exactMatchPredicates(exactMatches));
        where.append(andKindPredicate(modelTypes)).append(unitPredicate(unitKeys));
        return """
                MATCH (node:Model)
                %s
                %s
                ORDER BY fileId
                LIMIT $top
                """.formatted(where, groupedModelProjection("1.0"));
    }

    /**
     * The unit narrowing of the model queries (CML-1, design 4.4): nothing for an empty collection.
     *
     * <p>The contract is unchanged: the requested entries are ANDed - every entry must match - and
     * the matching keys of one entry are alternatives inside it (OR within an entry). A unit matches
     * when the model uses or defines it (depth 0) or when one of the units it uses is composed of it
     * ({@code centimetre} under {@code metre}).
     *
     * <p>The text is constant whatever the request names (QRY-12, decision 40): the entries travel in
     * one list parameter ({@link #unitParameters}), and a single {@code EXISTS} block loops over them.
     * It used to be one block per entry, which grew the statement with the request - the mechanism
     * {@code STO-7} closed for documents, against the rule in {@code AGENTS.md}.
     *
     * <p>It is an {@code EXISTS} subquery appended after the retrieval clause, so the full-text call
     * and the equality predicates still produce the candidate set and the units only narrow it.
     */
    static String unitPredicate(List<List<String>> unitKeys) {
        if (unitKeys == null || unitKeys.isEmpty()) {
            return "";
        }
        return " AND all(e IN $unitEntries WHERE EXISTS { MATCH (node)-[:USES_UNIT]->(u0:UNIT)"
                + " MATCH (u0)-[:COMPOSED_OF*0..]->(u:UNIT) WHERE u.key IN e.keys })";
    }

    /**
     * The parameter {@link #unitPredicate} reads: one list, one map per requested entry, each map
     * holding that entry's key set under {@code keys} in request order (QRY-12). Empty for no
     * entries, so a request without units still sends no unit parameter at all.
     *
     * <p>The entries travel in the parameter because per-entry grouping cannot be expressed without
     * one clause per entry: with the entries in the statement, the statement would grow with the
     * request (decision 40).
     */
    static Map<String, Object> unitParameters(List<List<String>> unitKeys) {
        if (unitKeys == null || unitKeys.isEmpty()) {
            return Map.of();
        }
        return Map.<String, Object>of("unitEntries", unitKeys.stream()
                .map(keys -> Map.<String, Object>of("keys", List.copyOf(keys)))
                .toList());
    }

    /**
     * The kind filter of the model queries: nothing for an empty collection (today's behaviour), one
     * kind as {@code node:SBML_MODEL}, several as a parenthesized OR. Kinds render in
     * {@link ModelType} declaration order, so the statement does not depend on the request order.
     * A kind without a label ({@code P2M}) is not a filter value and renders nothing.
     */
    static String kindPredicate(Collection<ModelType> modelTypes) {
        if (modelTypes == null || modelTypes.isEmpty()) {
            return "";
        }
        var labels = new ArrayList<String>();
        for (var modelType : ModelType.values()) {
            if (modelTypes.contains(modelType) && !kindLabel(modelType).isEmpty()) {
                labels.add("node:" + kindLabel(modelType).substring(1));
            }
        }
        if (labels.isEmpty()) {
            return "";
        }
        return labels.size() == 1 ? labels.getFirst() : "(" + String.join(OR, labels) + ")";
    }

    /** The kind filter as a {@code WHERE} continuation, or an empty string when there is none. */
    private static String andKindPredicate(Collection<ModelType> modelTypes) {
        var predicate = kindPredicate(modelTypes);
        return predicate.isEmpty() ? "" : AND + predicate;
    }

    /**
     * The aggregation every grouped model query shares: collect the matching versions per
     * {@code fileId}, pick the best-scoring one with {@code reduce} — not by collected order — and
     * expose the flat field names plus the version list. The document facts travel per collected
     * version, so the group reports the representative's values.
     */
    private static String groupedModelProjection(String scoreExpression) {
        return """
                OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)
                WITH node, coalesce(document, node) AS document, %s
                WITH coalesce(document.fileId, node.fileId) AS fileId,
                     collect({uid: coalesce(document.uid, node.uid),
                              versionId: coalesce(document.versionId, node.versionId),
                              score: %s,
                              modelId: coalesce(node.modelId, document.fileId),
                              modelName: coalesce(node.name, document.fileId),
                              documentUri: document.sourceUri,
                              filename: document.fileId,
                              documentStored: document.xmlDoc IS NOT NULL,
                              documentBytes: document.xmlDocBytes,
                              documentTruncated: document.xmlDocTruncated}) AS versions
                WITH fileId, versions,
                     reduce(best = versions[0], v IN versions | CASE WHEN v.score > best.score THEN v ELSE best END) AS best
                RETURN fileId, best.modelId AS modelId, best.modelName AS modelName, best.uid AS uid,
                       best.documentUri AS documentUri, best.filename AS filename, best.score AS score,
                       best.versionId AS versionId, best.documentStored AS documentStored,
                       best.documentBytes AS documentBytes, best.documentTruncated AS documentTruncated,
                       [v IN versions | {uid: v.uid, versionId: v.versionId, score: v.score}] AS versions
                """.formatted(scoreExpression, scoreExpression);
    }

    /**
     * The {@code OPTIONAL MATCH}, {@code WITH} and {@code RETURN} every flat model query shares. A
     * metadata-only {@code :Document:Model} node is its own document ({@code coalesce}), so the
     * document facts resolve there too.
     */
    private static String modelProjection(String scoreExpression) {
        var score = "score".equals(scoreExpression) ? "score" : scoreExpression + " AS score";
        return """
                OPTIONAL MATCH (document:Document)-[:HAS_MODEL]->(node)
                WITH node, coalesce(document, node) AS document, %s
                RETURN coalesce(node.uid, document.uid) AS uid,
                       coalesce(node.modelId, document.fileId) AS modelId,
                       coalesce(node.name, document.fileId) AS modelName,
                       document.sourceUri AS documentUri,
                       document.fileId AS filename,
                       coalesce(document.versionId, node.versionId) AS versionId,
                       document.xmlDoc IS NOT NULL AS documentStored,
                       document.xmlDocBytes AS documentBytes,
                       document.xmlDocTruncated AS documentTruncated,
                       score""".formatted(score);
    }

    /**
     * Renders one predicate per match: a single value is one equality, several values become a
     * parenthesized OR so the predicate needs no grouping of its own.
     */
    private static String exactMatchPredicates(List<ExactMatch> exactMatches) {
        var predicates = new ArrayList<String>(exactMatches.size());
        for (var index = 0; index < exactMatches.size(); index++) {
            var match = exactMatches.get(index);
            var equalities = new ArrayList<String>(match.values().size());
            for (var valueIndex = 0; valueIndex < match.values().size(); valueIndex++) {
                equalities.add("node." + match.property() + " = $"
                        + exactParameterName(index, valueIndex, match.values().size()));
            }
            predicates.add(equalities.size() == 1
                    ? equalities.getFirst()
                    : "(" + String.join(OR, equalities) + ")");
        }
        return String.join(AND, predicates);
    }

    /** Beyond a single value the parameter carries the value index: {@code exact0_1}. */
    private static String exactParameterName(int matchIndex, int valueIndex, int valueCount) {
        return valueCount == 1 ? "exact" + matchIndex : "exact" + matchIndex + "_" + valueIndex;
    }

    /** Binds {@code exact{i}} / {@code exact{i}_{j}}; HashMap, never Map.of, to be null-safe. */
    private static void putExactParameters(Map<String, Object> parameters, List<ExactMatch> exactMatches) {
        for (var index = 0; index < exactMatches.size(); index++) {
            var match = exactMatches.get(index);
            for (var valueIndex = 0; valueIndex < match.values().size(); valueIndex++) {
                parameters.put(
                        exactParameterName(index, valueIndex, match.values().size()),
                        match.values().get(valueIndex));
            }
        }
    }

    @Override
    public Optional<ModelDeletionResult> deleteByUid(String uid, String expectedFileId, String expectedVersionId) {
        var parameters = new HashMap<String, Object>();
        parameters.put("uid", uid);
        parameters.put("legacyUid", legacyUid(uid));
        try (var session = driver.session(sessionConfig)) {
            return session.executeWrite(tx -> {
                var document = tx.run(findByUidCypher(), parameters)
                        .list(Neo4jModelRepository::identifierFrom)
                        .stream()
                        .findFirst();
                if (document.isEmpty()) {
                    return Optional.<ModelDeletionResult>empty();
                }
                var found = document.get();
                requireMatch("fileId", expectedFileId, found.fileId(), found.uid());
                requireMatch("versionId", expectedVersionId, found.versionId(), found.uid());

                // The chain is relinked before the node goes, so a middle version leaves one chain.
                tx.run(ModelDeletionCypher.relinkVersionChainCypher(), Map.of("uid", found.uid())).consume();
                var nodes = tx.run(ModelDeletionCypher.deleteOwnedGraphCypher(), Map.of("uid", found.uid()))
                        .single()
                        .get("nodes")
                        .asLong();
                var purged = purgeOrphanAnnotations(tx) + purgeUnreachableUnits(tx);
                return Optional.of(new ModelDeletionResult(found.uid(), found.fileId(), 1, nodes + purged));
            });
        }
    }

    @Override
    public Optional<ModelDeletionResult> deleteByFileId(String fileId) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeWrite(tx -> {
                var record = tx.run(ModelDeletionCypher.deleteOwnedGraphsCypher(), Map.of("fileId", fileId))
                        .single();
                var documents = record.get("documents").asInt();
                if (documents == 0) {
                    return Optional.<ModelDeletionResult>empty();
                }
                var purged = purgeOrphanAnnotations(tx) + purgeUnreachableUnits(tx);
                return Optional.of(new ModelDeletionResult(
                        null, fileId, documents, record.get("nodes").asLong() + purged));
            });
        }
    }

    /** Legacy purged annotation nodes that lost their last relationship after a delete. */
    private static long purgeOrphanAnnotations(TransactionContext tx) {
        var purged = 0L;
        for (var label : ModelDeletionCypher.ORPHAN_LABELS) {
            purged += tx.run(ModelDeletionCypher.purgeOrphansCypher(label))
                    .single()
                    .get("purged")
                    .asLong();
        }
        return purged;
    }

    /**
     * Units are shared (CML-1), so they cannot be purged by label-and-no-relationship: the query
     * keeps the closure of every unit a surviving entity or model still names, and deletes the rest.
     */
    private static long purgeUnreachableUnits(TransactionContext tx) {
        return tx.run(ModelDeletionCypher.purgeUnreachableUnitsCypher())
                .single()
                .get("purged")
                .asLong();
    }

    /**
     * The legacy delete guards: a confirmation value, when given, has to match the stored document.
     * Legacy answered with {@code ok=false} and a message; modern refuses with a conflict.
     */
    private static void requireMatch(String property, String expected, String actual, String uid) {
        if (expected != null && !expected.equals(actual)) {
            throw ModelConflictException.guardViolation(
                    property + " " + expected + " does not match the stored document " + uid);
        }
    }

    private static boolean fileExists(TransactionContext tx, String fileId) {
        return tx.run("MATCH (d:Document {fileId: $fileId}) RETURN count(d) AS count", Map.of("fileId", fileId))
                .single()
                .get("count")
                .asLong() > 0;
    }

    private static ModelIdentifier identifierFrom(Record record) {
        return new ModelIdentifier(
                record.get("uid").asString(),
                record.get("fileId").asString(),
                record.get("versionId").isNull() ? null : record.get("versionId").asString(),
                record.get("legacyUid").isNull() ? null : record.get("legacyUid").asLong()
        );
    }

    private static ModelVersion versionFrom(Record record) {
        return new ModelVersion(
                record.get("uid").asString(),
                record.get("fileId").asString(),
                record.get("versionId").isNull() ? null : record.get("versionId").asString(),
                record.get("legacyUid").isNull() ? null : record.get("legacyUid").asLong()
        );
    }

    private static StoredDocument storedDocumentFrom(Record record) {
        return new StoredDocument(
                record.get("uid").asString(),
                record.get("fileId").asString(),
                record.get("versionId").isNull() ? null : record.get("versionId").asString(),
                record.get("legacyUid").isNull() ? null : record.get("legacyUid").asLong(),
                record.get("xmlDoc").isNull() ? null : record.get("xmlDoc").asString(),
                record.get("xmlDocEncoding").isNull() ? null : record.get("xmlDocEncoding").asString(),
                record.get("xmlDocBytes").isNull() ? 0L : record.get("xmlDocBytes").asLong(),
                !record.get("xmlDocTruncated").isNull() && record.get("xmlDocTruncated").asBoolean(),
                MetadataJson.parse(record.get("meta").isNull() ? null : record.get("meta").asString())
        );
    }

    /**
     * A legacy numeric uid is a decimal integer; a UUID never is. Values longer than 18 digits are
     * not legacy uids either and would overflow a long, so they stay string-only lookups.
     */
    private static Long legacyUid(String uid) {
        if (uid == null || uid.isEmpty() || uid.length() > 18) {
            return null;
        }
        for (var index = 0; index < uid.length(); index++) {
            var character = uid.charAt(index);
            if (character < '0' || character > '9') {
                return null;
            }
        }
        return Long.valueOf(uid);
    }
}
