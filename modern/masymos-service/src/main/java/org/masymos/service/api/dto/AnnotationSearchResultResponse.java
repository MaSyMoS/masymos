package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import org.masymos.core.query.AnnotationSearchResult;

@Schema(description = "A matched annotation with its related data models.")
public record AnnotationSearchResultResponse(
        @Schema(description = "Annotation resource URI.", example = "http://identifiers.org/uniprot/P00533")
        String uri,
        @Schema(description = "URIs of the models carrying this annotation.")
        List<URI> relatedModelUris,
        @Schema(description = "Relevance score of the match.", example = "0.87")
        double score
) {
    public static AnnotationSearchResultResponse from(AnnotationSearchResult result) {
        return new AnnotationSearchResultResponse(
                result.uri(),
                result.relatedModelUris(),
                result.score()
        );
    }
}
