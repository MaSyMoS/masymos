package org.masymos.core.archive;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Optional;

/**
 * Stable identity for a file inside a COMBINE archive: {@code <archive-uri>!/<entry-path>}.
 *
 * <p>Extracting archive entries to a temporary directory and indexing those file URIs would put
 * paths into the graph that stop existing the moment the run ends, and SED-ML references inside the
 * archive would never match. The {@code !/} form keeps the archive as the durable identity, survives
 * the run, and behaves correctly with {@link java.net.URI#resolve} for relative references such as
 * {@code ../models/adlung1.sbml}.
 *
 * <p>The entry path is an <em>identity</em>, not a spelling: {@link #normaliseEntryPath} reduces the
 * path a manifest, a packer or a URI-encoded reference happens to carry to one form, so
 * {@code models//a.sbml}, {@code ./models/a.sbml}, {@code models\a.sbml} and
 * {@code models/sub/../a.sbml} all name the same member. Two spellings of one member would otherwise
 * become two documents, and a spelling the archive does not contain would drop a readable entry.
 * Case is <em>not</em> folded: an archive is a zip, in which {@code Models/x.sbml} and
 * {@code models/x.sbml} are two different files.
 */
public final class CombineArchiveUri {

    public static final String SEPARATOR = "!/";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private CombineArchiveUri() {
    }

    public static URI of(java.net.URI archiveUri, String entryPath) {
        if (archiveUri == null) {
            throw new CombineArchiveException("archive URI must not be null");
        }
        if (entryPath == null || entryPath.isBlank()) {
            throw new CombineArchiveException("archive entry path must not be blank");
        }
        var normalised = normaliseEntryPath(entryPath);
        if (normalised.isEmpty()) {
            throw new CombineArchiveException(
                    "archive entry path escapes its archive or names no entry: " + entryPath);
        }
        return java.net.URI.create(archiveUri + SEPARATOR + normalised.get());
    }

    /**
     * Reduces an entry path to the one spelling that identifies a member of its archive.
     *
     * <p>The rules, in this order: trim surrounding whitespace; percent-decode exactly once (UTF-8)
     * and re-encode every segment so that only {@code A-Z a-z 0-9 - . _ ~} stay literal, which is what
     * makes {@code models/a%20b.sbml} and {@code models/a b.sbml} one identity; treat {@code \} as
     * {@code /}; drop empty segments and {@code .}; resolve {@code ..} by popping the previous
     * segment; keep case and every other character of a segment verbatim.
     *
     * @return the normalised path, or empty when the path is unusable: it carries malformed
     *     percent-encoding, it is not well-formed UTF-8, one of its segments holds a control
     *     character, it would escape its archive with {@code ..}, or it names no entry at all
     */
    public static Optional<String> normaliseEntryPath(String entryPath) {
        if (entryPath == null) {
            return Optional.empty();
        }
        var trimmed = entryPath.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        var decoded = percentDecodeOnce(trimmed);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        var segments = new ArrayDeque<String>();
        for (var segment : decoded.get().replace('\\', '/').split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    return Optional.empty();
                }
                segments.removeLast();
                continue;
            }
            if (segment.codePoints().anyMatch(Character::isISOControl)) {
                return Optional.empty();
            }
            segments.addLast(segment);
        }
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        var normalised = new StringBuilder(trimmed.length());
        for (var segment : segments) {
            if (!normalised.isEmpty()) {
                normalised.append('/');
            }
            normalised.append(encodeSegment(segment));
        }
        return Optional.of(normalised.toString());
    }

    public static boolean isArchiveUri(java.net.URI uri) {
        return uri != null && uri.toString().contains(SEPARATOR);
    }

    /**
     * Splits an archive URI into the archive and the entry path inside it.
     *
     * <p>Only the <em>first</em> separator splits, so a nested archive keeps its inner
     * {@code !/} in the entry path - {@code file:///data/a.zip!/b.zip!/x.sbml} parses to
     * {@code (file:///data/a.zip, b.zip!/x.sbml)}. The path returned is the normalised one
     * {@link #of} wrote, because it is the same string that is stored.
     */
    public static Optional<Location> parse(java.net.URI uri) {
        if (uri == null) {
            return Optional.empty();
        }
        var text = uri.toString();
        var index = text.indexOf(SEPARATOR);
        if (index <= 0 || index == text.length() - SEPARATOR.length()) {
            return Optional.empty();
        }
        return Optional.of(new Location(
                java.net.URI.create(text.substring(0, index)),
                text.substring(index + SEPARATOR.length())
        ));
    }

    /**
     * Decodes {@code %XX} sequences once, on the UTF-8 bytes of the input. A path is not a form body,
     * so {@code +} stays a plus.
     */
    private static Optional<String> percentDecodeOnce(String value) {
        var raw = value.getBytes(StandardCharsets.UTF_8);
        var decoded = new ByteArrayOutputStream(raw.length);
        for (var index = 0; index < raw.length; index++) {
            if (raw[index] != '%') {
                decoded.write(raw[index]);
                continue;
            }
            if (index + 2 >= raw.length) {
                return Optional.empty();
            }
            var high = Character.digit((char) raw[index + 1], 16);
            var low = Character.digit((char) raw[index + 2], 16);
            if (high < 0 || low < 0) {
                return Optional.empty();
            }
            decoded.write((high << 4) | low);
            index += 2;
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return Optional.of(decoder.decode(ByteBuffer.wrap(decoded.toByteArray())).toString());
        } catch (CharacterCodingException exception) {
            return Optional.empty();
        }
    }

    /**
     * Encodes one segment, leaving only RFC 3986's unreserved characters literal. The result is ASCII
     * and holds no {@code !}, {@code ?}, {@code #} or {@code /}, so it can neither split the entry
     * path nor be mistaken for the archive part of the URI.
     */
    private static String encodeSegment(String segment) {
        var encoded = new StringBuilder(segment.length());
        for (var value : segment.getBytes(StandardCharsets.UTF_8)) {
            var unsigned = value & 0xFF;
            if (isUnreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(HEX[unsigned >> 4]).append(HEX[unsigned & 0x0F]);
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    /** An archive URI split into the archive itself and the entry path inside it. */
    public record Location(java.net.URI archiveUri, String entryPath) {
    }
}
