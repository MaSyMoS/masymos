package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.masymos.core.query.PersonSummary;

@Schema(description = "Basic person details.")
public record PersonSummaryResponse(
        @Schema(description = "Given name.", example = "Ada")
        String givenName,
        @Schema(description = "Family name.", example = "Lovelace")
        String familyName,
        @Schema(description = "Contact email address.", example = "ada.lovelace@example.org")
        String email,
        @Schema(description = "Affiliated organization.", example = "Example University")
        String organization
) {
    public static PersonSummaryResponse from(PersonSummary person) {
        return new PersonSummaryResponse(
                person.givenName(),
                person.familyName(),
                person.email(),
                person.organization()
        );
    }
}
