package org.masymos.core.extract;

import java.util.ArrayList;
import java.util.List;

public record CellmlExternalCmeta(
        String modelAuthorOrg,
        List<List<String>> keywords,
        String citationTitle,
        List<List<String>> citationAuthors,
        String title,
        String citationJournal,
        String modelAuthor,
        String citationId
) {
    public CellmlExternalCmeta {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        citationAuthors = citationAuthors == null ? List.of() : List.copyOf(citationAuthors);
    }

    /**
     * The keywords as one flat, de-duplicated list. PMR serves {@code keywords} as a list of lists
     * ([] for most models, one inner list per keyword group otherwise); the inner lists carry no
     * meaning beyond grouping, so they are concatenated in document order. A keyword may itself be
     * a phrase such as {@code "calcium handling"} and keeps its spaces.
     */
    public List<String> flatKeywords() {
        var flat = new ArrayList<String>();
        for (var group : keywords) {
            if (group == null) {
                continue;
            }
            for (var keyword : group) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                var trimmed = keyword.trim();
                if (!flat.contains(trimmed)) {
                    flat.add(trimmed);
                }
            }
        }
        return List.copyOf(flat);
    }

    /**
     * The keywords as one space-separated string for a full-text property, or an empty string when
     * there are none. Never a {@link List#toString()}: the brackets and quotes are not text a user
     * searches for.
     */
    public String keywordsText() {
        return String.join(" ", flatKeywords());
    }
}
