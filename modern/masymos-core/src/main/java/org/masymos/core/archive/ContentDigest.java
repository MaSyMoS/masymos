package org.masymos.core.archive;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The provenance label a COMBINE archive entry gets: a short, stable digest of its bytes.
 *
 * <p>A document's {@code versionId} is the external version label legacy took from the caller - a
 * release name, a commit hash, a synthetic identifier from a bulk importer - and it is never parsed
 * and never ordered ({@link org.masymos.core.model.ModelReference}). Such a label is <em>not</em> the
 * specification version of the file: an OMEX manifest declares each entry's format, for example
 * {@code sbml.level-2.version-4}, and that level/version is read out of the file content by the
 * parser anyway ({@code sbmlLevel}/{@code sbmlVersion}, queryable as {@code SBML_LEVEL} and
 * {@code SBML_VERSION}).</p>
 *
 * <p>An archive states no version label at all, so the label is derived from the entry bytes. That is
 * the only value that changes when an archive is re-imported with new content: the same entry path in
 * two archive revisions yields two different labels, while an unchanged entry keeps its label, so the
 * documents stay distinguishable instead of both carrying no label (SBML-1).</p>
 *
 * <p>The label is {@code sha256-} followed by the first 16 hex characters of the SHA-256 digest. That
 * is 64 bits - two different entry contents sharing a label by accident is not a practical concern -
 * and short enough to be read in a URL path segment. The full digest can always be recomputed from
 * the archived bytes, so nothing is lost by truncating it.</p>
 */
public final class ContentDigest {
    static final String PREFIX = "sha256-";
    private static final int LABEL_BYTES = 8;

    private ContentDigest() {}

    /**
     * Returns the provenance label of {@code content}, for example {@code sha256-2cf24dba5fb0a30e}.
     * An empty entry still gets its documented label rather than none.
     */
    public static String versionLabel(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        return PREFIX + HexFormat.of().formatHex(sha256(content), 0, LABEL_BYTES);
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", exception);
        }
    }
}
