package org.masymos.neo4j;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.spi.UnitBackfill;
import org.masymos.core.units.UnitBackfillReport;
import org.masymos.core.units.UnitLayer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;

/**
 * The unit backfill over Neo4j (STO-6, decision 36): read the source document the graph already
 * stores, re-parse it, and write only its unit layer - no document, no model, no version link, no
 * entity, no annotation, no {@code ownerUid}.
 *
 * <p>Three consequences of that narrowness, all deliberate. It cannot append a version, so it is
 * safe on a corpus whose sources are gone. It is idempotent by construction, because every write is a
 * {@code MERGE} on the identity the normal write uses. And it is honest about its limit: a document
 * without a stored source, or with a truncated one, is counted as skipped instead of being guessed
 * at - a run that skipped anything never reports a complete corpus.
 *
 * <p>Why not re-index: that appends a version to the chain and needs the original source reachable
 * (decision 36). Why not reuse {@link ParsedModelGraphCypher}: it creates the whole document.
 */
public final class Neo4jUnitBackfill implements UnitBackfill {

    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jUnitBackfill(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public UnitBackfillReport run(boolean apply, int limit, String cursor) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be zero (unbounded) or positive, was " + limit);
        }
        try (var session = driver.session(sessionConfig)) {
            var totalDocuments = (int) count(session, UnitBackfillCypher.DOCUMENT_COUNT, Map.of());
            var storedDocuments = (int) count(session, UnitBackfillCypher.STORED_DOCUMENT_COUNT, Map.of());
            var stored = limit == UnitBackfill.UNBOUNDED
                    ? session.executeRead(tx -> tx.run(
                                    UnitBackfillCypher.STORED_DOCUMENTS,
                                    Values.parameters("after", cursor))
                            .list(StoredRow::from))
                    : session.executeRead(tx -> tx.run(
                                    UnitBackfillCypher.STORED_DOCUMENTS_LIMITED,
                                    Values.parameters("after", cursor, "limit", limit))
                            .list(StoredRow::from));

            int backfilled = 0;
            int alreadyComplete = 0;
            int truncated = 0;
            int unsupported = 0;
            int unparseable = 0;
            long unitsCreated = 0;
            long usesCreated = 0;
            long hasUnitsCreated = 0;
            long compositionsCreated = 0;
            long relationshipsSkipped = 0;

            // The page is cut only when the scan filled its bound; then the population after the last
            // uid is what this run leaves for a later one, and that uid is the cursor to resume from.
            var lastUid = stored.isEmpty() ? null : stored.get(stored.size() - 1).uid();
            var remaining = 0;
            String nextCursor = null;
            if (limit != UnitBackfill.UNBOUNDED && stored.size() == limit) {
                remaining = (int) count(
                        session, UnitBackfillCypher.STORED_DOCUMENTS_AFTER_COUNT, Map.of("after", lastUid));
                nextCursor = remaining > 0 ? lastUid : null;
            }

            for (var row : stored) {
                if (row.truncated()) {
                    truncated++;
                    continue;
                }
                var type = supportedType(row.modelType());
                if (type == null) {
                    unsupported++;
                    continue;
                }
                if (isAlreadyComplete(session, row.uid())) {
                    alreadyComplete++;
                    continue;
                }
                var layer = parse(row, type);
                if (layer == null) {
                    unparseable++;
                    continue;
                }
                if (layer.isEmpty()) {
                    // Nothing to write and nothing missing: an SBML model that states no unit is
                    // complete, not skipped, and re-parsing it on every run would be noise.
                    alreadyComplete++;
                    continue;
                }
                var contribution = measure(session, row.uid(), layer);
                if (apply) {
                    write(session, row.uid(), layer);
                }
                backfilled++;
                unitsCreated += contribution.units();
                usesCreated += contribution.uses();
                hasUnitsCreated += contribution.hasUnits();
                compositionsCreated += contribution.compositions();
                relationshipsSkipped += contribution.skipped();
            }

            return new UnitBackfillReport(
                    apply,
                    stored.size(),
                    remaining,
                    backfilled,
                    alreadyComplete,
                    totalDocuments - storedDocuments,
                    truncated,
                    unsupported,
                    unparseable,
                    (int) unitsCreated,
                    (int) usesCreated,
                    (int) hasUnitsCreated,
                    (int) compositionsCreated,
                    (int) relationshipsSkipped,
                    nextCursor,
                    cursor != null);
        }
    }

    private static ModelType supportedType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return null;
        }
        return switch (modelType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SBML" -> ModelType.SBML;
            case "CELLML" -> ModelType.CELLML;
            default -> null;
        };
    }

    private static boolean isAlreadyComplete(Session session, String uid) {
        return count(session, UnitBackfillCypher.ALREADY_COMPLETE, Map.of("uid", uid)) > 0;
    }

    /** Re-parses the stored source; {@code null} means "cannot be read", never "has no units". */
    private UnitLayer parse(StoredRow row, ModelType type) {
        var reference = new ModelReference(
                row.fileId(),
                sourceUri(row),
                type,
                row.versionId());
        var bytes = row.xml().getBytes(charset(row.encoding()));
        try (var stream = new ByteArrayInputStream(bytes)) {
            var source = new ModelSource(reference);
            var parsedModel = switch (type) {
                case SBML -> new SbmlModelParser().parse(source, stream);
                case CELLML -> new CellmlModelParser().parse(source, stream);
                default -> throw new IllegalArgumentException("no unit layer for " + type);
            };
            ExtractedModelGraph graph = new ParsedModelGraphMapper().toGraph(parsedModel);
            return UnitLayer.of(graph);
        } catch (java.io.IOException | RuntimeException exception) {
            return null;
        }
    }

    private static URI sourceUri(StoredRow row) {
        var uri = row.sourceUri();
        if (uri != null && !uri.isBlank()) {
            try {
                return URI.create(uri);
            } catch (IllegalArgumentException notAUri) {
                // fall through to the synthetic urn: the reference is only used for error messages
            }
        }
        return URI.create("urn:masymos:backfill:" + row.uid());
    }

    private static Charset charset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding.trim());
        } catch (RuntimeException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * What the write would create, measured against the graph as it is now. This is also the dry run:
     * the same four numbers, with nothing written.
     */
    private static Contribution measure(Session session, String uid, UnitLayer layer) {
        var keys = List.copyOf(layer.unitKeys());
        var present = keys.isEmpty()
                ? List.<String>of()
                : session.executeRead(tx -> tx.run(UnitBackfillCypher.MISSING_UNITS, Map.of("keys", keys))
                        .single()
                        .get("present")
                        .asList(value -> value.asString()));
        var missingUnits = keys.size() - present.size();

        var compositions = compositionBatch(layer);
        // An empty UNWIND produces no row at all, so `single()` on a count query would throw: the
        // empty batch is short-circuited instead of being sent to the database.
        var missingCompositions = compositions.isEmpty()
                ? 0L
                : count(session, UnitBackfillCypher.MISSING_COMPOSITIONS, Map.of("compositions", compositions));

        var missingUses = layer.uses().isEmpty()
                ? 0L
                : count(session, UnitBackfillCypher.MISSING_USES, Map.of("uid", uid, "uses", layer.uses()));

        var missingHasUnits = 0L;
        var skipped = 0L;
        for (var entry : hasUnitsByLabel(layer).entrySet()) {
            var batch = entry.getValue();
            var missing = count(
                    session,
                    UnitBackfillCypher.missingHasUnits(entry.getKey()),
                    Map.of("uid", uid, "hasUnits", batch));
            var resolvable = count(
                    session,
                    UnitBackfillCypher.resolvableHasUnits(entry.getKey()),
                    Map.of("uid", uid, "hasUnits", batch));
            missingHasUnits += missing;
            skipped += batch.size() - resolvable;
        }
        return new Contribution(missingUnits, missingUses, missingHasUnits, missingCompositions, skipped);
    }

    private static void write(Session session, String uid, UnitLayer layer) {
        session.executeWriteWithoutResult(tx -> {
            tx.run(UnitBackfillCypher.WRITE_UNITS, Map.of("units", unitBatch(layer))).consume();
            if (!layer.compositions().isEmpty()) {
                tx.run(UnitBackfillCypher.WRITE_COMPOSITIONS, Map.of("compositions", compositionBatch(layer)))
                        .consume();
            }
            if (!layer.uses().isEmpty()) {
                tx.run(UnitBackfillCypher.WRITE_USES, Map.of("uid", uid, "uses", layer.uses())).consume();
            }
            for (var entry : hasUnitsByLabel(layer).entrySet()) {
                tx.run(
                                UnitBackfillCypher.writeHasUnits(entry.getKey()),
                                Map.of("uid", uid, "hasUnits", entry.getValue()))
                        .consume();
            }
        });
    }

    private static List<Map<String, Object>> compositionBatch(UnitLayer layer) {
        return layer.compositions().stream()
                .map(composition -> Map.<String, Object>of(
                        "start", composition.startKey(),
                        "end", composition.endKey(),
                        "exponent", composition.exponent(),
                        "multiplier", composition.multiplier()))
                .toList();
    }

    private static List<Map<String, Object>> unitBatch(UnitLayer layer) {
        var batch = new ArrayList<Map<String, Object>>();
        for (var unit : layer.unitNodes()) {
            batch.add(Map.of("key", unit.key(), "properties", new LinkedHashMap<>(unit.properties())));
        }
        return List.copyOf(batch);
    }

    /** HAS_UNIT edges grouped by entity label, because a Cypher label cannot be a parameter. */
    private static Map<String, List<Map<String, Object>>> hasUnitsByLabel(UnitLayer layer) {
        var byLabel = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var entity : layer.hasUnits()) {
            byLabel.computeIfAbsent(entity.label(), label -> new ArrayList<>())
                    .add(Map.of(
                            "id", entity.id(),
                            "unitKey", entity.unitKey(),
                            "properties", new LinkedHashMap<>(entity.properties())));
        }
        return byLabel;
    }

    private static long count(Session session, String cypher, Map<String, Object> parameters) {
        return session.executeRead(tx -> tx.run(cypher, parameters).single().get("count").asLong());
    }

    /** What one document's write contributes, so the report can separate created from skipped. */
    private record Contribution(long units, long uses, long hasUnits, long compositions, long skipped) {
    }

    /** One row of {@link UnitBackfillCypher#STORED_DOCUMENTS}. */
    private record StoredRow(
            String uid,
            String fileId,
            String sourceUri,
            String modelType,
            String versionId,
            String encoding,
            boolean truncated,
            String xml) {

        static StoredRow from(org.neo4j.driver.Record record) {
            return new StoredRow(
                    record.get("uid").asString(),
                    record.get("fileId").isNull() ? record.get("uid").asString() : record.get("fileId").asString(),
                    record.get("sourceUri").isNull() ? null : record.get("sourceUri").asString(),
                    record.get("modelType").isNull() ? null : record.get("modelType").asString(),
                    record.get("versionId").isNull() ? null : record.get("versionId").asString(),
                    record.get("encoding").isNull() ? null : record.get("encoding").asString(),
                    !record.get("truncated").isNull() && record.get("truncated").asBoolean(),
                    record.get("xml").asString());
        }
    }
}
