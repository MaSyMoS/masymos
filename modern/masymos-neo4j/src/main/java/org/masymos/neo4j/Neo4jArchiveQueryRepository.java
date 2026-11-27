package org.masymos.neo4j;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import org.masymos.core.query.ArchiveSearchResult;
import org.masymos.core.query.IndexText;
import org.masymos.core.query.ModelQueryRequest;
import org.masymos.core.query.Page;
import org.masymos.core.query.PageCursor;
import org.masymos.core.query.QueryField;
import org.masymos.core.query.QueryPlan;
import org.masymos.core.spi.ArchiveQueryRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;

/**
 * COMBINE archives from {@code archiveFulltextV1}, with the number of documents each one bundles. The
 * archive node is written once, so it has no exact copies: {@code ARCHIVE_NAME} and
 * {@code ARCHIVE_URI} render as Lucene clauses on {@code fileId} and {@code sourceUri}, and a bare
 * keyword term searches both. Every archive request is therefore a full-text request — there is no
 * exact-only variant to shortcut.
 */
public final class Neo4jArchiveQueryRepository implements ArchiveQueryRepository {
    /** The lane name of the cursor fingerprint; it must differ per lane or one lane pages another. */
    static final String ARCHIVES = "archives";

    /**
     * The archive order key: the uid, else the {@code sourceUri}, else the empty string, so a missing
     * property still orders and cannot drop the row out of a later page.
     */
    private static final String ARCHIVE_IDENTITY = "coalesce(node.uid, node.sourceUri, '')";

    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jArchiveQueryRepository(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    @Override
    public List<ArchiveSearchResult> queryArchives(ModelQueryRequest request) {
        return queryArchivesPage(request).hits();
    }

    /**
     * The archive query as a keyset page ({@code OPN-6}), continuing by the same rule the model lane uses:
     * a total order ({@code score DESC, nodeIdentity}), a statement asked for one row beyond {@code top},
     * and the identity projected under {@code nodeIdentity} so the cursor can name the position.
     */
    @Override
    public Page<ArchiveSearchResult> queryArchivesPage(ModelQueryRequest request) {
        var plan = plan(request);
        if (plan.isEmpty()) {
            return Page.last(List.of());
        }
        var cursor = PagedHits.cursorOf(request, ARCHIVES);
        var parameters = new HashMap<String, Object>();
        parameters.put("keyword", plan.textQuery());
        parameters.put("minScore", minScore(request));
        parameters.put("limit", request.top() + 1);
        if (cursor != null) {
            parameters.put("cursorScore", cursor.score());
            parameters.put("cursorIdentity", cursor.identity());
        }
        try (var session = driver.session(sessionConfig)) {
            var cypher = archiveCypher(cursor);
            var rows = session.executeRead(tx -> tx.run(cypher, parameters).list(record -> new PagedHits.Hit<>(
                    result(record),
                    record.get("score").asDouble(),
                    record.get("nodeIdentity").asString())));
            return PagedHits.page(rows, request, ARCHIVES);
        }
    }

    /**
     * Splits the request into the Lucene expression for the archive index. {@code ARCHIVE_NAME}
     * targets {@code fileId}, {@code ARCHIVE_URI} targets {@code sourceUri}, and a {@code KEYWORD}
     * clause (or any other field) is a bare escaped term, exactly as the model planner renders one.
     */
    static QueryPlan plan(ModelQueryRequest request) {
        var text = new StringBuilder();
        if (request.keyword() != null && !request.keyword().isBlank()) {
            append(text, escape(request.keyword().trim()));
        }
        request.clauses().forEach(clause -> {
            var value = clause.keyword() == null ? "" : clause.keyword().trim();
            if (clause.field() == QueryField.ARCHIVE_NAME) {
                append(text, "fileId:(" + escape(value) + ")");
                return;
            }
            if (clause.field() == QueryField.ARCHIVE_URI) {
                append(text, "sourceUri:(" + escape(value) + ")");
                return;
            }
            append(text, escape(value));
        });
        return new QueryPlan(text.toString(), List.of());
    }

    private static void append(StringBuilder text, String rendered) {
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(rendered);
    }

    private static String escape(String value) {
        var escaped = new StringBuilder();
        var clamped = IndexText.shrinkToMaxTermLength(value);
        for (var index = 0; index < clamped.length(); index++) {
            var character = clamped.charAt(index);
            if (isSpecialCharacter(character)) {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    private static boolean isSpecialCharacter(char character) {
        return switch (character) {
            case '+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/' -> true;
            default -> false;
        };
    }

    /** A null floor means "no cut", expressed as {@code 0.0} because the Cypher parameter is required. */
    private static double minScore(ModelQueryRequest request) {
        return request.minScore() == null ? 0.0 : request.minScore();
    }

    /**
     * The archive query. {@code HAS_DOCUMENT} is optional, so an archive without documents reports a
     * count of {@code 0} instead of vanishing from the result.
     */
    static String archiveCypher() {
        return archiveCypher(null);
    }

    static String archiveCypher(PageCursor cursor) {
        return """
                CALL db.index.fulltext.queryNodes('archiveFulltextV1', $keyword)
                YIELD node, score
                WHERE score >= $minScore
                %s
                """.formatted(archiveProjection(cursor));
    }

    /**
     * The projection every archive query shares: the archive row and its document count. The identity is
     * bound in the {@code WITH} that binds {@code score}, and the keyset's {@code WHERE} follows it: that
     * is the one place both are in scope, and a filter there runs before {@code HAS_DOCUMENT} is walked,
     * so the page counts archives, not their bundled documents.
     */
    private static String archiveProjection(PageCursor cursor) {
        return """
                WITH node AS archive, score, %s AS nodeIdentity
                %s
                OPTIONAL MATCH (archive)-[:HAS_DOCUMENT]->(document)
                RETURN coalesce(archive.uid, archive.sourceUri) AS uid,
                       archive.fileId AS fileId,
                       archive.sourceUri AS sourceUri,
                       score,
                       count(DISTINCT document) AS documentCount,
                       nodeIdentity
                ORDER BY score DESC, nodeIdentity
                LIMIT $limit
                """.formatted(ARCHIVE_IDENTITY, keyset(cursor));
    }

    /** The keyset appended to the filtering {@code WHERE} when a cursor was supplied, else nothing. */
    private static String keyset(PageCursor cursor) {
        return cursor == null
                ? ""
                : "WHERE (score < $cursorScore OR (score = $cursorScore AND " + ARCHIVE_IDENTITY
                        + " > $cursorIdentity))";
    }

    private static ArchiveSearchResult result(Record record) {
        var count = record.get("documentCount");
        return new ArchiveSearchResult(
                record.get("uid").asString(null),
                record.get("fileId").asString(null),
                uri(record.get("sourceUri")),
                record.get("score").asDouble(),
                count == null || count.isNull() ? 0L : count.asLong());
    }

    /** A null guard: an archive without a {@code sourceUri} property reports no URI, not a crash. */
    private static URI uri(Value value) {
        return value == null || value.isNull() ? null : URI.create(value.asString());
    }
}
