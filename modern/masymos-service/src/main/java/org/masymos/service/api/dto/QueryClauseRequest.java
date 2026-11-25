package org.masymos.service.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.masymos.core.query.QueryClause;
import org.masymos.core.query.QueryField;

@Schema(description = "A single search clause: a field plus a search term.")
public record QueryClauseRequest(
        @NotNull @Schema(description = "Field the term is matched against.", example = "MODEL_NAME")
        QueryField field,
        @NotBlank @Schema(description = "Term to match in the given field.", example = "glycolysis")
        String keyword
) {
    public QueryClause toQueryClause() {
        return new QueryClause(field, keyword);
    }
}
