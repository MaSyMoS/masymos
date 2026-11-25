package org.masymos.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.swagger.v3.core.converter.ModelConverters;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.masymos.service.api.dto.QueryModelsRequest;

/**
 * CML-1 on the API surface: the unit filter is an optional list on the model query request, it
 * reaches the domain request unchanged, an unknown unit is a {@code 400} naming the value, and the
 * OpenAPI schema documents the field.
 */
class QueryModelsUnitsTest {

    @Test
    void theUnitNamesReachTheDomainRequest() {
        var request = new QueryModelsRequest("calcium", List.of(), 10, null, null, null, List.of("metre", "cm"));

        assertEquals(List.of("metre", "cm"), request.toModelQueryRequest().units());
    }

    @Test
    void aNullEntryIsDroppedRatherThanFailingTheRequest() {
        var request = new QueryModelsRequest(
                "calcium", List.of(), 10, null, null, null, java.util.Arrays.asList("metre", null, " "));

        assertEquals(List.of("metre"), request.toModelQueryRequest().units());
    }

    @Test
    void anUnknownUnitIsA400NamingTheValue() {
        var problem = new ApiExceptionHandler()
                .handleIllegalArgument(new IllegalArgumentException("unknown unit: furlong_per_fortnight"));

        assertEquals(400, problem.getStatus());
        assertTrue(problem.getDetail().contains("furlong_per_fortnight"), problem.getDetail());
    }

    @Test
    void theOpenApiSchemaDocumentsUnits() {
        var schema = ModelConverters.getInstance().readAll(QueryModelsRequest.class).get("QueryModelsRequest");

        assertNotNull(schema, "no QueryModelsRequest schema");
        assertTrue(schema.getProperties().containsKey("units"), schema.getProperties().toString());
        var units = (io.swagger.v3.oas.models.media.Schema<?>) schema.getProperties().get("units");
        assertTrue(units.getDescription().contains("prefix forms"), units.getDescription());
    }
}
