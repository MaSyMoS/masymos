package org.masymos.core.extract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ParsedPublication(
        String pubId,
        String title,
        String journal,
        String abstractText,
        String affiliation,
        String year,
        List<ParsedPerson> authors
) {
    public ParsedPublication {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }

    public Map<String, String> properties() {
        var properties = new LinkedHashMap<String, String>();
        properties.put("pubId", nullToEmpty(pubId));
        properties.put("title", nullToEmpty(title));
        properties.put("journal", nullToEmpty(journal));
        properties.put("abstractText", nullToEmpty(abstractText));
        properties.put("affiliation", nullToEmpty(affiliation));
        properties.put("year", nullToEmpty(year));
        return Map.copyOf(properties);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
