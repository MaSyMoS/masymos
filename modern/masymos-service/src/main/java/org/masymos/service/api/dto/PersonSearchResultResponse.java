package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import org.masymos.core.query.PersonSearchResult;

@Schema(description = "A matched person with the models they are linked to.")
public record PersonSearchResultResponse(
        @Schema(description = "The matched person.")
        PersonSummaryResponse person,
        @Schema(description = "URIs of the models linked to this person.")
        List<URI> relatedModelUris,
        @Schema(description = "Relevance score of the match.", example = "0.87")
        double score
) {
    public static PersonSearchResultResponse from(PersonSearchResult result) {
        return new PersonSearchResultResponse(
                PersonSummaryResponse.from(result.person()),
                result.relatedModelUris(),
                result.score()
        );
    }
}
