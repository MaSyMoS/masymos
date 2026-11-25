package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import org.masymos.core.query.PublicationSearchResult;

@Schema(description = "A matched publication with its authors and linked models.")
public record PublicationSearchResultResponse(
        @Schema(description = "Publication title.", example = "A model of something")
        String title,
        @Schema(description = "Journal the publication appeared in.", example = "Example Journal")
        String journal,
        @Schema(description = "Affiliation reported for the publication.", example = "Example University")
        String affiliation,
        @Schema(description = "Publication year.", example = "2014")
        String year,
        @Schema(description = "Authors of the publication.")
        List<PersonSummaryResponse> authors,
        @Schema(description = "URIs of the models linked to this publication.")
        List<URI> relatedModelUris,
        @Schema(description = "Relevance score of the match.", example = "0.87")
        double score
) {
    public static PublicationSearchResultResponse from(PublicationSearchResult result) {
        return new PublicationSearchResultResponse(
                result.title(),
                result.journal(),
                result.affiliation(),
                result.year(),
                result.authors().stream().map(PersonSummaryResponse::from).toList(),
                result.relatedModelUris(),
                result.score()
        );
    }
}
