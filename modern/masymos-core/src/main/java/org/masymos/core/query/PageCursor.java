package org.masymos.core.query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The opaque position of a keyset page ({@code OPN-6}): the sort key of the last row a page answered plus
 * a fingerprint of the request that produced it, so a cursor replayed against a different query is refused
 * instead of answering a page of somebody else's result set.
 *
 * <p>Why a keyset and not an offset: an offset re-counts the population on every request, so a document
 * indexed between two pages shifts every later row and the sweep repeats or skips - the drift
 * {@code CORP14-3} cost a session. The keyset names a <em>position in the order</em>.
 *
 * <p>Why the sort key is a node property and not a projected alias: a {@code WHERE} runs before the
 * projection, so a keyset on {@code coalesce(document.fileId, node.fileId)} is unexpressible without
 * rewriting the projections into {@code WITH} chains. The properties this record carries are in scope
 * already. On the model lane the identity is {@code elementId(node)}: it is the only value that also
 * separates two versions of one file, and a re-index rewrites the nodes and invalidates the cursor, which
 * is right because it changed the scores too.
 *
 * <p>The encoding is deliberately not readable: base64url of {@code score|identity|fingerprint}. It is a
 * position, not a secret, and a caller that edits it gets a {@code 400} rather than a wrong page.
 */
public record PageCursor(double score, String identity, String fingerprint) {

    /** A cursor whose lane has a constant score (an exact-only lane answers {@code 1.0}). */
    public static PageCursor ofIdentity(String identity, String fingerprint) {
        return new PageCursor(1.0, identity, fingerprint);
    }

    /** The wire form: base64url of {@code score|identity|fingerprint}, the identity percent-escaped. */
    public String encode() {
        var raw = score + "|" + escape(identity) + "|" + fingerprint;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a cursor a caller sent back.
     *
     * @throws IllegalArgumentException if it is not a cursor this class wrote; the API turns that into a
     *     {@code 400} naming the parameter
     */
    public static PageCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("cursor is blank");
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("cursor is not a cursor this service issued");
        }
        var parts = raw.split("\\|", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("cursor is not a cursor this service issued");
        }
        double score;
        try {
            score = Double.parseDouble(parts[0]);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("cursor is not a cursor this service issued");
        }
        return new PageCursor(score, unescape(parts[1]), parts[2]);
    }

    /**
     * Refuses a cursor minted for another request.
     *
     * @throws IllegalArgumentException if the fingerprints differ; the message names the reason, not the
     *     other request, because a cursor is not a way to read somebody else's query
     */
    public PageCursor requireFingerprint(String expected) {
        if (!fingerprint.equals(expected)) {
            throw new IllegalArgumentException(
                    "cursor belongs to a different request; omit it to start at the beginning");
        }
        return this;
    }

    /**
     * A short, stable fingerprint of the discriminating fields of a request. Deliberately not the
     * record's hash: that changes between JVM runs and would refuse every cursor a restarted service had
     * issued.
     */
    public static String fingerprint(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("%", "%25").replace("|", "%7C");
    }

    private static String unescape(String value) {
        return value == null ? "" : value.replace("%7C", "|").replace("%25", "%");
    }
}
