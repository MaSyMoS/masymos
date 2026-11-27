package org.masymos.neo4j;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.masymos.core.extract.CellmlModelParser;
import org.masymos.core.extract.ExtractedModelGraph;
import org.masymos.core.extract.ModelSource;
import org.masymos.core.extract.ParsedModelGraphMapper;
import org.masymos.core.extract.SbmlModelParser;
import org.masymos.core.extract.SedmlModelParser;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;
import org.masymos.core.rederive.ReDeriveReport;
import org.masymos.core.spi.ReDerive;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;

/**
 * The re-derive sweep over Neo4j ({@code OPN-7}): for each stored source document, re-parse it, run the
 * current mapper, and compare what it yields with what the graph holds.
 *
 * <p>Two modes, one page contract. {@link #run(int, String)} measures and writes nothing;
 * {@link #apply(int, String)} also replaces the derived graph of every document it calls stale, one
 * transaction per document, through {@link Neo4jExtractedModelGraphRepository#repair} - so a repaired
 * document keeps its {@code uid}, {@code versionId}, chain position and stored bytes and the run writes
 * no new version. A document the scan calls {@code current} is not touched, which is why a second run
 * over the same page applies nothing.
 *
 * <p>What it can and cannot see, stated because a report that overclaims is worse than none. It counts
 * nodes per label set, so a construct the write path gained since the document was indexed shows up as
 * a shortfall. It does not compare properties, so a document that has every node but lacks a
 * <em>property</em> the mapper now writes (the {@code *Exact} copies, the model-search aggregates) is
 * reported only through the other signal: its owned nodes carry no {@code derivedKey}, which is true of
 * every document indexed before 2026-11-26 and is what a repair needs first anyway.
 *
 * <p>Shared nodes ({@code RESOURCE}, {@code PERSON}, {@code PUBLICATION}, {@code UNIT}) are left out of
 * the shortfall, because they are not owned by a document: a repair merges them on their natural key,
 * and the unit layer has its own backfill ({@code STO-6}).
 */
public final class Neo4jReDerive implements ReDerive {

    /** The label sets a document owns; a set touching one of these is shared and not compared. */
    private static final Set<String> SHARED_NODE_LABELS = Set.of("RESOURCE", "PERSON", "PUBLICATION", "UNIT");

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final Neo4jExtractedModelGraphRepository graphs;

    public Neo4jReDerive(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
        this.graphs = new Neo4jExtractedModelGraphRepository(driver, database);
    }

    @Override
    public ReDeriveReport run(int limit, String cursor) {
        return sweep(limit, cursor, false);
    }

    /** The same bounded page, repairing every document the scan calls stale ({@code OPN-7} slice 2). */
    @Override
    public ReDeriveReport apply(int limit, String cursor) {
        return sweep(limit, cursor, true);
    }

    private ReDeriveReport sweep(int limit, String cursor, boolean repair) {
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be zero (unbounded) or positive, was " + limit);
        }
        try (var session = driver.session(sessionConfig)) {
            var totalDocuments = (int) count(session, UnitBackfillCypher.DOCUMENT_COUNT, Map.of());
            var storedDocuments = (int) count(session, UnitBackfillCypher.STORED_DOCUMENT_COUNT, Map.of());
            var stored = limit == ReDeriveReport.UNBOUNDED
                    ? session.executeRead(tx -> tx.run(
                                    UnitBackfillCypher.STORED_DOCUMENTS, Values.parameters("after", cursor))
                            .list(StoredRow::from))
                    : session.executeRead(tx -> tx.run(
                                    UnitBackfillCypher.STORED_DOCUMENTS_LIMITED,
                                    Values.parameters("after", cursor, "limit", limit))
                            .list(StoredRow::from));

            var lastUid = stored.isEmpty() ? null : stored.get(stored.size() - 1).uid();
            var remaining = 0;
            String nextCursor = null;
            if (limit != ReDeriveReport.UNBOUNDED && stored.size() == limit) {
                remaining = (int) count(
                        session, UnitBackfillCypher.STORED_DOCUMENTS_AFTER_COUNT, Map.of("after", lastUid));
                nextCursor = remaining > 0 ? lastUid : null;
            }

            int stale = 0;
            int current = 0;
            int truncated = 0;
            int unsupported = 0;
            int unparseable = 0;
            int missingDerivedKeys = 0;
            int applied = 0;
            var missingByLabel = new TreeMap<String, Integer>();

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
                var graph = parse(row, type);
                if (graph == null) {
                    unparseable++;
                    continue;
                }
                var missing = 0;
                var withoutKey = 0;
                for (var entry : ownedNodeCounts(graph).entrySet()) {
                    var state = ownedState(session, row.uid(), entry.getKey());
                    var shortfall = Math.max(0, entry.getValue() - state.present());
                    if (shortfall > 0) {
                        missingByLabel.merge(entry.getKey(), shortfall, Integer::sum);
                    }
                    missing += shortfall;
                    withoutKey += state.withoutKey();
                }
                missingDerivedKeys += withoutKey;
                if (missing > 0 || withoutKey > 0) {
                    stale++;
                    // One transaction per document, and only for a document this scan called stale:
                    // a second run over the same page finds it current and repairs nothing.
                    if (repair) {
                        graphs.repair(graph, row.uid());
                        applied++;
                    }
                } else {
                    current++;
                }
            }

            return new ReDeriveReport(
                    stored.size(),
                    stale,
                    current,
                    truncated,
                    unsupported,
                    unparseable,
                    totalDocuments - storedDocuments,
                    remaining,
                    missingByLabel,
                    missingDerivedKeys,
                    nextCursor,
                    cursor != null,
                    applied);
        }
    }

    private static ModelType supportedType(String modelType) {
        if (modelType == null || modelType.isBlank()) {
            return null;
        }
        return switch (modelType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SBML" -> ModelType.SBML;
            case "CELLML" -> ModelType.CELLML;
            case "SEDML" -> ModelType.SEDML;
            // OWL is read through a conversion path of its own (OWL-2) and P2M is a mapping file
            // without a model kind; neither is re-parsed here, and the report counts them instead.
            default -> null;
        };
    }

    /** Re-parses the stored source; {@code null} means "cannot be read", never "nothing to compare". */
    private static ExtractedModelGraph parse(StoredRow row, ModelType type) {
        var reference = new ModelReference(row.fileId(), sourceUri(row), type, row.versionId());
        var bytes = row.xml().getBytes(charset(row.encoding()));
        try (var stream = new ByteArrayInputStream(bytes)) {
            var source = new ModelSource(reference);
            var parsedModel = switch (type) {
                case SBML -> new SbmlModelParser().parse(source, stream);
                case CELLML -> new CellmlModelParser().parse(source, stream);
                case SEDML -> new SedmlModelParser().parse(source, stream);
                default -> throw new IllegalArgumentException("no re-derive for " + type);
            };
            return new ParsedModelGraphMapper().toGraph(parsedModel);
        } catch (java.io.IOException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * How many nodes of each owned label set the mapper yields. The document node is skipped (the graph
     * already has it), and a label set touching a shared label is skipped because no document owns it.
     */
    private static Map<String, Integer> ownedNodeCounts(ExtractedModelGraph graph) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var node : graph.nodes()) {
            if (node.key().equals("document")) {
                continue;
            }
            if (node.labels().stream().anyMatch(SHARED_NODE_LABELS::contains)) {
                continue;
            }
            counts.merge(labelKey(node.labels()), 1, Integer::sum);
        }
        return counts;
    }

    /** The label set as the scan keys it: sorted names, so two runs of one document agree. */
    private static String labelKey(Set<String> labels) {
        var sorted = new ArrayList<>(labels);
        sorted.sort(String::compareTo);
        return String.join(":", sorted);
    }

    private static NodeState ownedState(Session session, String uid, String labelKey) {
        var labels = List.of(labelKey.split(":"));
        return session.executeRead(tx -> {
            var record = tx.run(ReDeriveCypher.ownedNodeState(labels), Map.of("uid", uid))
                    .single();
            return new NodeState(record.get("present").asInt(), record.get("withoutKey").asInt());
        });
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
        return URI.create("urn:masymos:rederive:" + row.uid());
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

    private static long count(Session session, String cypher, Map<String, Object> parameters) {
        return session.executeRead(tx -> tx.run(cypher, parameters).single().get("count").asLong());
    }

    /** What the graph holds for one label set of one document. */
    private record NodeState(int present, int withoutKey) {
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
