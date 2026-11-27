package org.masymos.neo4j;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.masymos.core.annotation.AnnotationTermCache;
import org.masymos.core.annotation.TermDefinition;
import org.masymos.core.annotation.TermKey;
import org.masymos.core.annotation.TermStatus;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;

/**
 * The persisted {@link AnnotationTermCache}: one {@code :ONTOLOGY_TERM} node per distinct term, shared
 * across documents and never owned by one.
 *
 * <p>One read and one write per annotation run ({@link AnnotationTermCacheCypher}), because the run
 * resolves its terms once before the per-resource loop. Enum values are stored and read by their
 * constant name ({@code RESOLVED}/{@code UNRESOLVED}, {@code BIOPORTAL}/{@code OLS4}/{@code PAGE},
 * {@code DEFINITIVE}/{@code TRANSIENT}), and {@code fetchedAt} is written as a Neo4j datetime that
 * reads back as an {@link Instant}, which is what the TTL measures against. The lookup
 * {@code catalogueFingerprint} is stored alongside as a plain string so a stale negative answer can be
 * recognised later; an absent property reads back as {@code null}, which marks an entry written before
 * the fingerprint existed.
 *
 * <p>This class degrades rather than throws: a read failure is a cold cache and a write failure
 * loses the cache, never the run — a run whose cache cannot be written still indexes its resources.
 */
public final class Neo4jAnnotationTermCache implements AnnotationTermCache {

    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jAnnotationTermCache(Driver driver, String database) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.builder().withDatabase(database).build();
    }

    /** The entries that exist for {@code keys}; keys without a stored entry are absent. */
    @Override
    public Map<TermKey, CachedTerm> findAll(Collection<TermKey> keys) {
        var wired = wire(keys);
        if (wired.isEmpty()) {
            return Map.of();
        }

        List<CachedTerm> rows;
        try (var session = driver.session(sessionConfig)) {
            rows = session.executeRead(tx -> tx.run(AnnotationTermCacheCypher.findAll(), Map.of("keys", wired))
                    .list(Neo4jAnnotationTermCache::toCachedTerm));
        } catch (RuntimeException exception) {
            // An unreadable cache is a cold cache: the caller retries every term next run.
            return Map.of();
        }

        var found = new LinkedHashMap<TermKey, CachedTerm>();
        for (var row : rows) {
            if (row != null) {
                found.put(row.key(), row);
            }
        }
        return found;
    }

    /** Upserts one entry per term in a single write transaction. */
    @Override
    public void saveAll(Collection<CachedTerm> terms) {
        var rows = rows(terms);
        if (rows.isEmpty()) {
            return;
        }
        try (var session = driver.session(sessionConfig)) {
            session.executeWriteWithoutResult(tx -> tx.run(AnnotationTermCacheCypher.saveAll(), Map.of("terms", rows))
                    .consume());
        } catch (RuntimeException exception) {
            // A failed cache write costs the cache, never the run.
        }
    }

    /**
     * The {@code $keys} parameter: the wire form of every non-null key, once each.
     *
     * <p>Duplicate keys are harmless to the read but pointless in the parameter list, and a {@code null}
     * element would bind a {@code null} key that matches nothing.
     */
    private static List<String> wire(Collection<TermKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        var wired = new ArrayList<String>();
        for (var key : keys) {
            if (key != null && !wired.contains(key.wire())) {
                wired.add(key.wire());
            }
        }
        return wired;
    }

    /** The {@code $terms} parameter: one map per complete entry, because an incomplete one cannot be stored. */
    private static List<Map<String, Object>> rows(Collection<CachedTerm> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (var term : terms) {
            if (term != null && term.definition() != null && term.fetchedAt() != null) {
                rows.add(toRow(term));
            }
        }
        return rows;
    }

    private static Map<String, Object> toRow(CachedTerm term) {
        var definition = term.definition();
        var key = definition.key();
        var row = new LinkedHashMap<String, Object>();
        row.put("key", key.wire());
        row.put("prefix", key.prefix());
        row.put("id", key.id());
        row.put("status", definition.status().name());
        row.put("keywordText", definition.keywordText());
        row.put("source", definition.source() == null ? null : definition.source().name());
        row.put("failureClass", definition.failureClass() == null ? null : definition.failureClass().name());
        row.put("fetchedAt", ZonedDateTime.ofInstant(term.fetchedAt(), ZoneOffset.UTC));
        row.put("failureReason", definition.failureReason());
        // Always present, possibly null: the Cypher SET then clears a stored fingerprint when an entry
        // is rewritten without one, instead of leaving a stale value behind.
        row.put("catalogueFingerprint", term.catalogueFingerprint());
        // The lifecycle status (D11). `termObsolete` is written as a boolean for every entry, the other
        // three as null when absent, which makes Neo4j remove them: a rescan that stops reporting a
        // predecessor cannot leave the old successor on the node.
        var termStatus = definition.termStatus();
        row.put("termObsolete", termStatus.obsolete());
        row.put("termReplacedBy", termStatus.replacedBy());
        row.put("termReason", termStatus.reason());
        row.put("termStatusSource", termStatus.source());
        return row;
    }

    /**
     * Reads one stored row, or {@code null} when it cannot be understood.
     *
     * <p>A {@code null} or blank {@code failureClass} is tolerated for a resolved definition, since the
     * design stores {@code null} there; for an unresolved one the absent class becomes
     * {@link TermDefinition.FailureClass#TRANSIENT}, which is a miss on every read, so a damaged entry is
     * retried instead of being trusted for a whole TTL. A row whose key, status or {@code fetchedAt}
     * cannot be read is dropped rather than guessed at.
     */
    private static CachedTerm toCachedTerm(Record record) {
        var key = parseKey(text(record.get("key")));
        var status = enumValue(TermDefinition.Status.class, text(record.get("status")));
        if (key == null || status == null) {
            return null;
        }
        var fetchedAt = instantOf(record.get("fetchedAt"));
        if (fetchedAt == null) {
            return null;
        }

        TermDefinition definition;
        try {
            if (status == TermDefinition.Status.RESOLVED) {
                var source = enumValue(TermDefinition.Source.class, text(record.get("source")));
                definition = TermDefinition.resolved(key, text(record.get("keywordText")), source,
                        termStatus(record));
            } else {
                var failureClass = enumValue(TermDefinition.FailureClass.class, text(record.get("failureClass")));
                if (failureClass == null) {
                    failureClass = TermDefinition.FailureClass.TRANSIENT;
                }
                definition = TermDefinition.unresolved(key, failureClass, text(record.get("failureReason")));
            }
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return CachedTerm.of(definition, fetchedAt, text(record.get("catalogueFingerprint")));
    }

    /**
     * The lifecycle status a stored row carries, or {@link TermStatus#none()} when it carries none.
     *
     * <p>The cache stores it so a warm hit keeps reporting an obsolete or merged id (D11) instead of
     * silently degrading to "nothing unusual". An entry written before ANNO-1e has none of the four
     * properties and reads back as {@code none()}, which is the truth for it: nothing was known.
     */
    private static TermStatus termStatus(Record record) {
        var obsolete = booleanOf(record.get("termObsolete"));
        var replacedBy = text(record.get("termReplacedBy"));
        var reason = text(record.get("termReason"));
        var source = text(record.get("termStatusSource"));
        if (!obsolete && replacedBy == null && reason == null && source == null) {
            return TermStatus.none();
        }
        return new TermStatus(obsolete, replacedBy, reason, source);
    }

    /** A stored boolean, or {@code false} when the property is absent or not a boolean. */
    private static boolean booleanOf(Value value) {
        return value != null && !value.isNull() && Boolean.TRUE.equals(value.asObject());
    }

    /** Splits the wire form {@code prefix:id}; anything that is not exactly that shape is unusable. */
    private static TermKey parseKey(String wire) {
        if (wire == null) {
            return null;
        }
        var separator = wire.indexOf(':');
        if (separator <= 0 || separator >= wire.length() - 1) {
            return null;
        }
        try {
            return new TermKey(wire.substring(0, separator), wire.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** A Neo4j datetime reads back as a {@link ZonedDateTime}; anything else is not a usable timestamp. */
    private static Instant instantOf(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        var raw = value.asObject();
        if (raw instanceof ZonedDateTime zoned) {
            return zoned.toInstant();
        }
        if (raw instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    /** A stored text property, or {@code null} when it is absent, null or blank. */
    private static String text(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        var rendered = value.asString();
        return rendered == null || rendered.isBlank() ? null : rendered;
    }
}
