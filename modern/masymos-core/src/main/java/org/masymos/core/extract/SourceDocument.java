package org.masymos.core.extract;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The source document of an indexed model, as it was read, together with the facts needed to serve
 * it back unchanged.
 *
 * <p>Legacy MaSyMoS wrote the document location into the {@code XMLDOC} property; the modern store
 * keeps the content instead, so a client can download what was indexed. Neo4j properties cannot
 * hold bytes, so the content is stored as text in one of two encodings:
 *
 * <ul>
 *   <li>{@code UTF-8} when the bytes decode as UTF-8 — the normal case, and readable in the graph;
 *   <li>{@code ISO-8859-1} when they do not — every byte maps to exactly one character, so
 *       {@link #contentBytes()} reproduces the input regardless of its real declared encoding.
 * </ul>
 *
 * <p>{@link #originalBytes()} is the size of the document before any truncation, so an oversized
 * document is reported instead of silently shortened (STO-2).
 */
public record SourceDocument(String content, String encoding, long originalBytes, boolean truncated) {
    public static final String UTF_8 = "UTF-8";
    public static final String BYTE_PRESERVING = "ISO-8859-1";

    /**
     * Default ceiling for one stored document: 10 MiB. Large enough for every model document in the
     * corpus, small enough that a single re-index cannot flood the graph store.
     */
    public static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;

    public SourceDocument {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(encoding, "encoding");
        if (originalBytes < 0) {
            throw new IllegalArgumentException("originalBytes must not be negative");
        }
    }

    /**
     * Captures at most {@code maxBytes} bytes of the document. A document that fits is kept whole;
     * a larger one is cut at a character boundary and flagged, and the original size is preserved.
     */
    public static SourceDocument from(byte[] bytes, int maxBytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        var text = decodeUtf8(bytes);
        var encoding = text == null ? BYTE_PRESERVING : UTF_8;
        if (bytes.length <= maxBytes) {
            var whole = text == null ? new String(bytes, Charset.forName(encoding)) : text;
            return new SourceDocument(whole, encoding, bytes.length, false);
        }
        var storedLength = text == null ? maxBytes : completeUtf8Prefix(bytes, maxBytes);
        var stored = Arrays.copyOf(bytes, storedLength);
        return new SourceDocument(new String(stored, Charset.forName(encoding)), encoding, bytes.length, true);
    }

    /** The document in the encoding it was stored with; byte-identical to what was indexed. */
    public byte[] contentBytes() {
        return content.getBytes(Charset.forName(encoding));
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return null;
        }
    }

    /**
     * The largest prefix of at most {@code limit} bytes that does not end inside a multi-byte UTF-8
     * sequence. Only called for bytes that are valid UTF-8 as a whole, so the only broken sequence
     * can be the one the cut created.
     */
    private static int completeUtf8Prefix(byte[] bytes, int limit) {
        var index = limit - 1;
        var continuation = 0;
        while (index >= 0 && (bytes[index] & 0xC0) == 0x80) {
            index--;
            continuation++;
        }
        if (index < 0) {
            return limit;
        }
        var expected = utf8SequenceLength(bytes[index]);
        return expected == 0 || continuation + 1 >= expected ? limit : index;
    }

    private static int utf8SequenceLength(byte lead) {
        var value = lead & 0xFF;
        if (value < 0x80) {
            return 1;
        }
        if ((value & 0xE0) == 0xC0) {
            return 2;
        }
        if ((value & 0xF0) == 0xE0) {
            return 3;
        }
        if ((value & 0xF8) == 0xF0) {
            return 4;
        }
        return 0;
    }
}
