package org.masymos.core.extract;

import java.util.List;
import java.util.Map;

public record ParsedAnnotation(
        String subjectKey,
        Map<String, String> properties,
        List<ParsedAnnotationResource> resources,
        List<ParsedPerson> creators,
        List<String> sboTerms
) {
}
