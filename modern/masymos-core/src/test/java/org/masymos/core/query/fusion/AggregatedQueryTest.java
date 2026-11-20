package org.masymos.core.query.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.masymos.core.model.ModelType;
import org.masymos.core.query.QueryEntity;

class AggregatedQueryTest {
    private static final Set<QueryEntity> FUSABLE =
            Set.of(QueryEntity.MODEL, QueryEntity.ANNOTATION, QueryEntity.PERSON, QueryEntity.PUBLICATION);

    private static AggregatedQuery sixArgument() {
        return new AggregatedQuery("novak", null, 5, null, "borda", FusionWeights.none());
    }

    @Test
    void theSixArgumentConstructorYieldsTheFourFusableEntities() {
        var query = sixArgument();

        assertEquals(FUSABLE, query.types());
        assertEquals(Set.of(), query.modelTypes());
        assertEquals("novak", query.keyword());
        assertEquals(5, query.top());
    }

    @Test
    void anExplicitTypeSelectionSurvives() {
        var query = new AggregatedQuery(
                "novak", null, 5, null, "borda", FusionWeights.none(), Set.of(QueryEntity.MODEL), Set.of());

        assertEquals(Set.of(QueryEntity.MODEL), query.types());
        assertEquals(Set.of(), query.modelTypes());
    }

    @Test
    void aNullTypeSelectionDefaultsToTheFusableEntities() {
        var query = new AggregatedQuery(
                "novak", null, 5, null, "borda", FusionWeights.none(), null, null);

        assertEquals(FUSABLE, query.types());
        assertEquals(Set.of(), query.modelTypes());
    }

    @Test
    void anEmptyTypeSelectionIsRejectedNamingTheAcceptedEntities() {
        var thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new AggregatedQuery(
                        "novak", null, 5, null, "borda", FusionWeights.none(), Set.of(), Set.of()));

        String message = thrown.getMessage();
        for (QueryEntity entity : FUSABLE) {
            assertTrue(message.contains(entity.wire()), message);
        }
        assertTrue(message.contains("accepted"), message);
    }

    @Test
    void sedmlCannotBeFused() {
        var thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new AggregatedQuery(
                        "novak",
                        null,
                        5,
                        null,
                        "borda",
                        FusionWeights.none(),
                        Set.of(QueryEntity.MODEL, QueryEntity.SEDML),
                        Set.of()));

        assertTrue(thrown.getMessage().contains(QueryEntity.SEDML.wire()), thrown.getMessage());
    }

    @Test
    void archiveCannotBeFused() {
        var thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new AggregatedQuery(
                        "novak",
                        null,
                        5,
                        null,
                        "borda",
                        FusionWeights.none(),
                        Set.of(QueryEntity.ARCHIVE),
                        Set.of()));

        String message = thrown.getMessage();
        assertTrue(message.contains(QueryEntity.ARCHIVE.wire()), message);
        assertTrue(message.contains(QueryEntity.MODEL.wire()), message);
    }

    @Test
    void owlIsNotAFilterableModelKind() {
        var thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new AggregatedQuery(
                        "novak",
                        null,
                        5,
                        null,
                        "borda",
                        FusionWeights.none(),
                        Set.of(QueryEntity.MODEL),
                        Set.of(ModelType.OWL)));

        String message = thrown.getMessage();
        assertTrue(message.contains("SBML"), message);
        assertTrue(message.contains("CELLML"), message);
    }

    @Test
    void bothFilterableModelKindsSurvive() {
        var query = new AggregatedQuery(
                "novak",
                null,
                5,
                null,
                "borda",
                FusionWeights.none(),
                Set.of(QueryEntity.MODEL),
                Set.of(ModelType.SBML, ModelType.CELLML));

        assertEquals(Set.of(ModelType.SBML, ModelType.CELLML), query.modelTypes());
        assertEquals(Set.of(QueryEntity.MODEL), query.types());
    }
}
