package org.masymos.service.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.masymos.core.query.FieldCatalog;
import org.masymos.core.query.QueryEntity;
import org.masymos.core.query.QueryField;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(
        name = "Metadata",
        description = "Discovery of the query fields supported by the search endpoints.")
public class QueryMetadataController {
    @Operation(
            summary = "List query fields",
            description = "Returns the enum names of all query fields that can be used in model search requests.")
    @ApiResponse(responseCode = "200", description = "Query field names returned.")
    @GetMapping("/api/v1/query-fields")
    public List<String> queryFields() {
        return Arrays.stream(QueryField.values()).map(Enum::name).toList();
    }

    @Operation(
            summary = "List the query fields of one entity",
            description = "Returns the enum names of the query fields that are meaningful for the given entity, in enum"
                    + " order. The catalog is discovery only: every endpoint keeps accepting any query field. Known"
                    + " entities: model, sedml, annotation, person, publication, archive. There is no constituent"
                    + " entity: COMPONENT, VARIABLE, COMPARTMENT, SPECIES, REACTION and PARAMETER are model fields,"
                    + " because the model index carries those properties flattened and scores them at model level"
                    + " (decision D3, QRY-6).")
    @ApiResponse(responseCode = "200", description = "Query field names for the entity returned.")
    @ApiResponse(responseCode = "400", description = "The entity type is unknown.")
    @GetMapping("/api/v1/query-fields/{type}")
    public List<String> queryFieldsForType(@PathVariable("type") String type) {
        return FieldCatalog.fieldsFor(QueryEntity.fromWire(type)).stream()
                .sorted(Comparator.comparingInt(Enum::ordinal))
                .map(Enum::name)
                .toList();
    }
}
