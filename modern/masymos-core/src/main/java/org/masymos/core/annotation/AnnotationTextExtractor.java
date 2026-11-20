package org.masymos.core.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns fetched annotation payloads into normalized searchable text.
 *
 * <p>The extracted text is index-only enrichment. It never replaces the original annotation URI and
 * is not authoritative stored content.
 */
public final class AnnotationTextExtractor {

    public static final int DEFAULT_MAX_TEXT_LENGTH = 10_000;

    private final int maxTextLength;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnnotationTextExtractor() {
        this(DEFAULT_MAX_TEXT_LENGTH);
    }

    public AnnotationTextExtractor(int maxTextLength) {
        this.maxTextLength = Math.max(1, maxTextLength);
    }

    public String extract(String contentType, String body) {
        if (body == null || body.isBlank()) {
            return "";
        }

        var text = switch (formatOf(contentType)) {
            case "html" -> stripMarkup(body, true);
            case "xml" -> stripMarkup(body, false);
            case "json" -> jsonText(body);
            default -> collapseWhitespace(body);
        };
        return truncate(text);
    }

    public String formatOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "text";
        }
        var lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("json")) {
            return "json";
        }
        if (lower.contains("html")) {
            return "html";
        }
        if (lower.contains("xml")) {
            return "xml";
        }
        return "text";
    }

    private static String stripMarkup(String body, boolean html) {
        var withoutBlocks = html
                ? body.replaceAll("(?is)<script.*?</script>", " ").replaceAll("(?is)<style.*?</style>", " ")
                : body;
        return collapseWhitespace(decodeEntities(withoutBlocks.replaceAll("<[^>]*>", " ")));
    }

    private String jsonText(String body) {
        try {
            var values = new ArrayList<String>();
            collectScalars(objectMapper.readTree(body), values);
            return truncate(collapseWhitespace(String.join(" ", values)));
        } catch (RuntimeException | java.io.IOException exception) {
            return collapseWhitespace(body.replaceAll("[{}\\[\\]\",:]", " "));
        }
    }

    private static void collectScalars(JsonNode node, List<String> values) {
        if (node == null) {
            return;
        }
        if (node.isValueNode()) {
            if (!node.isNull()) {
                var value = node.asText().trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectScalars(child, values));
            return;
        }
        node.fields().forEachRemaining(entry -> collectScalars(entry.getValue(), values));
    }

    private static String decodeEntities(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String text) {
        return text.length() <= maxTextLength ? text : text.substring(0, maxTextLength);
    }
}
