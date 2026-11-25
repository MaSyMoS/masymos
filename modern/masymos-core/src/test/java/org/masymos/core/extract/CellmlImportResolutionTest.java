package org.masymos.core.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.masymos.core.extract.ParsedModelEntity.EntityType;
import org.masymos.core.model.ModelReference;
import org.masymos.core.model.ModelType;

/**
 * CML-2: a CellML import must not be a silent empty shell. One {@code CELLML_IMPORT} entity per
 * {@code <import>}, one {@code CELLML_IMPORTED_COMPONENT} per {@code <importedComponent>}, and an
 * explicit {@code resolved}/{@code resolution} outcome. Assertions name entity TYPES and variable
 * names, never a substring of some rendered output.
 */
class CellmlImportResolutionTest {
    private static final String FIXTURE_DIRECTORY = "cellml-imports";
    private static final URI HOST_URI = URI.create("file:///fixtures/cellml-imports/host.cellml");
    private static final String SODIUM_HREF = "sodium.cellml";
    private static final String MISSING_HREF = "missing.cellml";
    /** An imported component's entity id is scoped by its import; its name stays the plain alias. */
    private static final String SODIUM_COMPONENT_ID = "sodium.cellml#sodium_channel";
    private static final String POTASSIUM_COMPONENT_ID = "missing.cellml#potassium_channel";

    @Test
    void defaultParserRecordsImportAsUnresolvedInsteadOfSilentlyDroppingIt() throws IOException {
        var parsed = parse(new CellmlModelParser(), "host.cellml");

        var imported = entity(parsed, EntityType.CELLML_IMPORT, SODIUM_HREF);
        assertEquals("sodium.cellml", imported.properties().get("href"));
        assertEquals("false", imported.properties().get("resolved"));
        assertEquals("no-resolver", imported.properties().get("resolution"));

        var sodium = entity(parsed, EntityType.CELLML_IMPORTED_COMPONENT, SODIUM_COMPONENT_ID);
        assertEquals("sodium_channel", sodium.name());
        assertEquals("sodium_channel", sodium.properties().get("componentRef"));
        assertEquals(SODIUM_HREF, sodium.properties().get("importId"));
        assertEquals("false", sodium.properties().get("resolved"));
        assertEquals("no-resolver", sodium.properties().get("resolution"));

        assertTrue(variablesOf(parsed, EntityType.CELLML_IMPORTED_COMPONENT, SODIUM_COMPONENT_ID).isEmpty(),
                "an unresolved imported component must carry no variables");

        assertEquals(2, count(parsed, EntityType.CELLML_IMPORT), "one CELLML_IMPORT per <import>");
        assertEquals(2, count(parsed, EntityType.CELLML_IMPORTED_COMPONENT));
        assertEquals(1, count(parsed, EntityType.COMPONENT),
                "the local component stays the only COMPONENT; an import is not one");
        assertEquals("2", parsed.properties().get("cellml.imports"));
        assertEquals("2", parsed.properties().get("cellml.importedComponents"));
        assertEquals("1", parsed.properties().get("cellml.importedUnits"));
        assertEquals("1", parsed.properties().get("cellml.components"),
                "cellml.components keeps counting local components only");
        assertEquals("2", parsed.properties().get("cellml.variables"),
                "cellml.variables keeps counting local variables only");
    }

    @Test
    void fileResolverAttachesTheTargetComponentsVariables() throws IOException {
        var parsed = parse(fileResolvingParser(), "host.cellml");

        var imported = entity(parsed, EntityType.CELLML_IMPORT, SODIUM_HREF);
        assertEquals("true", imported.properties().get("resolved"));
        assertNull(imported.properties().get("resolution"), "a resolved import writes no reason");

        var sodium = entity(parsed, EntityType.CELLML_IMPORTED_COMPONENT, SODIUM_COMPONENT_ID);
        assertEquals("true", sodium.properties().get("resolved"));
        assertNull(sodium.properties().get("resolution"));

        var variables = variablesOf(parsed, EntityType.CELLML_IMPORTED_COMPONENT, SODIUM_COMPONENT_ID);
        assertEquals(List.of("V", "h", "m"), names(variables));
        assertTrue(variables.stream().allMatch(variable ->
                "true".equals(variable.properties().get("imported"))
                        && SODIUM_HREF.equals(variable.properties().get("importedFrom"))
                        && "true".equals(variable.properties().get("resolved"))));
        var m = variable(variables, "m");
        assertEquals("out", m.properties().get("publicInterface"));
        assertEquals("none", m.properties().get("privateInterface"));
        assertEquals("dimensionless", m.properties().get("units"));
        var h = variable(variables, "h");
        assertEquals("none", h.properties().get("publicInterface"));
        assertEquals("in", h.properties().get("privateInterface"));

        var importedVariableKeys = parsed.entityRelationships().stream()
                .filter(relationship -> "HAS_VARIABLE".equals(relationship.type()))
                .filter(relationship -> relationship.startEntityKey()
                        .equals(EntityType.CELLML_IMPORTED_COMPONENT + ":" + SODIUM_COMPONENT_ID))
                .map(ParsedEntityRelationship::endEntityKey)
                .toList();
        assertEquals(3, importedVariableKeys.size(),
                "every target variable hangs off the imported component");
    }

    @Test
    void missingImportTargetIsExplicitlyUnresolved() throws IOException {
        var parsed = parse(fileResolvingParser(), "host.cellml");

        var imported = entity(parsed, EntityType.CELLML_IMPORT, MISSING_HREF);
        assertEquals("false", imported.properties().get("resolved"));
        assertEquals("not-found", imported.properties().get("resolution"));

        var potassium = entity(parsed, EntityType.CELLML_IMPORTED_COMPONENT, POTASSIUM_COMPONENT_ID);
        assertEquals("potassium_channel", potassium.name());
        assertEquals("false", potassium.properties().get("resolved"));
        assertEquals("not-found", potassium.properties().get("resolution"));
        assertTrue(variablesOf(parsed, EntityType.CELLML_IMPORTED_COMPONENT, POTASSIUM_COMPONENT_ID).isEmpty());

        var sodium = entity(parsed, EntityType.CELLML_IMPORTED_COMPONENT, SODIUM_COMPONENT_ID);
        assertEquals("true", sodium.properties().get("resolved"),
                "one missing target must not spoil the other import");
    }

    /**
     * The local component and the imported one share the name {@code sodium_channel}. Entities are
     * keyed {@code type:id}, so they must stay two nodes with their own variables. This runs through
     * {@link ParsedModelGraphMapper} because that is where a merge would actually happen.
     */
    @Test
    void importedComponentNeverMergesWithTheLocalComponentOfTheSameName() throws IOException {
        var parsed = parse(fileResolvingParser(), "name-clash.cellml");

        var local = entity(parsed, EntityType.COMPONENT, "sodium_channel");
        var imported = entity(parsed, EntityType.CELLML_IMPORTED_COMPONENT, SODIUM_COMPONENT_ID);
        assertEquals("sodium_channel", local.name());
        assertEquals("sodium_channel", imported.name());
        assertFalse(local.properties().containsKey("imported"),
                "the local component stays a plain local component");

        assertEquals(List.of("m", "x"),
                names(variablesOf(parsed, EntityType.COMPONENT, "sodium_channel")));
        assertEquals(List.of("V", "h", "m"),
                names(variablesOf(parsed, EntityType.CELLML_IMPORTED_COMPONENT, SODIUM_COMPONENT_ID)));

        var graph = new ParsedModelGraphMapper().toGraph(parsed);
        var localNode = node(graph, "component:sodium_channel");
        var importedNode = node(graph, "cellml_imported_component:" + SODIUM_COMPONENT_ID);
        assertEquals(Set.of("CELLML_COMPONENT"), localNode.labels());
        assertEquals(Set.of("CELLML_IMPORTED_COMPONENT"), importedNode.labels());
        assertEquals("true", importedNode.properties().get("resolved"));

        assertEquals(Set.of("m", "x"), variableNames(graph, localNode.key()));
        assertEquals(Set.of("V", "h", "m"), variableNames(graph, importedNode.key()));

        var importNode = node(graph, "cellml_import:" + SODIUM_HREF);
        assertEquals(Set.of("CELLML_IMPORT"), importNode.labels());
        assertEquals("true", importNode.properties().get("resolved"));
        assertTrue(graph.relationships().stream().anyMatch(relationship ->
                relationship.startKey().equals(importNode.key())
                        && "HAS_IMPORTED_COMPONENT".equals(relationship.type())
                        && relationship.endKey().equals(importedNode.key())),
                "the import must reach its imported component in the graph");
    }

    /**
     * CellML 1.0/1.1 spell the element {@code <component component_ref=...>}, not
     * {@code <importedComponent>}; the real 1.1 fixture proves both names are read. Its units-only
     * import has nothing to resolve, so it must say so instead of looking resolved.
     */
    @Test
    void readsCellml11ImportsAndReportsAUnitsOnlyImportExplicitly() throws IOException {
        var reference = new ModelReference("fixture", HOST_URI, ModelType.CELLML, null, Map.of());
        var fixture = fixtureRoot().resolve("../pmr/c6b/GLUT2_BG.cellml").normalize();
        ParsedModel parsed;
        try (InputStream stream = Files.newInputStream(fixture)) {
            parsed = new CellmlModelParser().parse(new ModelSource(reference), stream);
        }

        var unitsOnly = entity(parsed, EntityType.CELLML_IMPORT, "units/units.cellml");
        assertEquals("false", unitsOnly.properties().get("resolved"));
        assertEquals("no-imported-components", unitsOnly.properties().get("resolution"));

        var componentImport = entity(parsed, EntityType.CELLML_IMPORT, "params_BG.cellml");
        assertEquals("false", componentImport.properties().get("resolved"));
        assertEquals("no-resolver", componentImport.properties().get("resolution"));

        assertEquals("2", parsed.properties().get("cellml.imports"));
        assertEquals("1", parsed.properties().get("cellml.importedComponents"));
        assertEquals("5", parsed.properties().get("cellml.importedUnits"));
        assertEquals("params_BG",
                entity(parsed, EntityType.CELLML_IMPORTED_COMPONENT, "params_BG.cellml#params_BG").name());
    }

    /**
     * CML-1: an imported {@code <units>} declaration is a unit of the importing document, reduced in
     * the target's own scope. Before this, every imported unit was {@code kind=unresolved}: the
     * resolver covered components only, so a resolved import and a broken one looked the same.
     */
    @Test
    void anImportedUnitsDeclarationResolvesThroughTheTargetDocument() throws IOException {
        var parsed = parse(fileResolvingParser(), "units-import-host.cellml");

        var units = parsed.entities().stream()
                .filter(entity -> entity.type() == EntityType.UNIT)
                .toList();
        assertTrue(
                units.stream().noneMatch(unit -> "unresolved".equals(unit.properties().get("kind"))),
                "a resolvable units import must not leave an unresolved node behind: " + units);

        var perMs = units.stream()
                .filter(unit -> "per_ms".equals(unit.properties().get("display")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no per_ms unit in " + units));
        assertEquals("derived", perMs.properties().get("kind"));
        assertEquals("second^-1", perMs.properties().get("dimension"));
        assertEquals("1000", perMs.properties().get("multiplier"), "1/(0.001 s)");

        assertTrue(
                parsed.entityRelationships().stream()
                        .anyMatch(relationship -> "HAS_UNIT".equals(relationship.type())
                                && relationship.startEntityKey().equals("VARIABLE:transporter/k")
                                && relationship.endEntityKey().equals("UNIT:" + perMs.properties().get("key"))
                                && "per_ms".equals(relationship.properties().get("declaredName"))),
                "the variable uses the imported unit: " + parsed.entityRelationships());

        var imported = entity(parsed, EntityType.CELLML_IMPORT, "units-lib.cellml");
        assertEquals("true", imported.properties().get("resolved"), "its units did resolve");
        assertEquals("1", parsed.properties().get("cellml.importedUnits"));
    }

    /**
     * The same import without a resolver: still visible, still not resolved, and now with a reason
     * instead of only {@code kind=unresolved}.
     */
    @Test
    void withoutAResolverAnImportedUnitsDeclarationStaysUnresolvedWithAReason() throws IOException {
        var parsed = parse(new CellmlModelParser(), "units-import-host.cellml");

        var unresolved = parsed.entities().stream()
                .filter(entity -> entity.type() == EntityType.UNIT)
                .filter(entity -> "unresolved".equals(entity.properties().get("kind")))
                .toList();
        assertEquals(1, unresolved.size(), unresolved.toString());
        assertEquals("units-lib.cellml", unresolved.getFirst().properties().get("href"));
        assertEquals("per_ms", unresolved.getFirst().properties().get("units_ref"));
        assertEquals("no-resolver", unresolved.getFirst().properties().get("resolution"));
    }

    /**
     * A units_ref the target document does not declare is a real failure, not a silent drop: the
     * node names the target, the reference and the reason. {@code sodium.cellml} declares no
     * {@code per_ms}, so {@code host.cellml} is exactly that case.
     */
    @Test
    void anImportedUnitsReferenceTheTargetDoesNotDeclareStaysUnresolvedWithAReason() throws IOException {
        var parsed = parse(fileResolvingParser(), "host.cellml");

        var unresolved = parsed.entities().stream()
                .filter(entity -> entity.type() == EntityType.UNIT)
                .filter(entity -> "unresolved".equals(entity.properties().get("kind")))
                .toList();
        assertEquals(1, unresolved.size(), unresolved.toString());
        assertEquals(SODIUM_HREF, unresolved.getFirst().properties().get("href"));
        assertEquals("per_ms", unresolved.getFirst().properties().get("units_ref"));
        assertEquals("units-missing", unresolved.getFirst().properties().get("resolution"));

        var sodium = entity(parsed, EntityType.CELLML_IMPORT, SODIUM_HREF);
        assertEquals("true", sodium.properties().get("resolved"),
                "its component resolved, so one missing unit must not deny the whole import");
    }

    private static CellmlModelParser fileResolvingParser() {
        return new CellmlModelParser(CellmlExternalCmetaResolver.NONE, new FileCellmlImportResolver(fixtureRoot()));
    }

    private static ParsedModel parse(CellmlModelParser parser, String fixture) throws IOException {
        var reference = new ModelReference("fixture", HOST_URI, ModelType.CELLML, null, Map.of());
        try (InputStream stream = Files.newInputStream(fixtureRoot().resolve(fixture))) {
            return parser.parse(new ModelSource(reference), stream);
        }
    }

    private static Path fixtureRoot() {
        var url = CellmlImportResolutionTest.class.getClassLoader().getResource(FIXTURE_DIRECTORY);
        assertNotNull(url, "fixture directory " + FIXTURE_DIRECTORY + " must be on the test classpath");
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ParsedModelEntity entity(ParsedModel parsed, EntityType type, String id) {
        return parsed.entities().stream()
                .filter(candidate -> candidate.type() == type && candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " entity with id " + id + "; have "
                        + parsed.entities().stream()
                                .filter(candidate -> candidate.type() == type)
                                .map(ParsedModelEntity::id)
                                .toList()));
    }

    private static long count(ParsedModel parsed, EntityType type) {
        return parsed.entities().stream().filter(candidate -> candidate.type() == type).count();
    }

    private static List<ParsedModelEntity> variablesOf(ParsedModel parsed, EntityType ownerType, String ownerId) {
        var ownerKey = ownerType + ":" + ownerId;
        var variableKeys = parsed.entityRelationships().stream()
                .filter(relationship -> "HAS_VARIABLE".equals(relationship.type()))
                .filter(relationship -> relationship.startEntityKey().equals(ownerKey))
                .map(ParsedEntityRelationship::endEntityKey)
                .toList();
        return parsed.entities().stream()
                .filter(candidate -> candidate.type() == EntityType.VARIABLE)
                .filter(candidate -> variableKeys.contains(EntityType.VARIABLE + ":" + candidate.id()))
                .toList();
    }

    private static ParsedModelEntity variable(List<ParsedModelEntity> variables, String name) {
        return variables.stream()
                .filter(candidate -> name.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no variable " + name + " in " + names(variables)));
    }

    private static List<String> names(List<ParsedModelEntity> entities) {
        return entities.stream().map(ParsedModelEntity::name).sorted().toList();
    }

    private static ExtractedGraphNode node(ExtractedModelGraph graph, String key) {
        return graph.nodes().stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no graph node " + key + "; have "
                        + graph.nodes().stream().map(ExtractedGraphNode::key).toList()));
    }

    private static Set<String> variableNames(ExtractedModelGraph graph, String ownerKey) {
        var variableKeys = graph.relationships().stream()
                .filter(relationship -> "HAS_VARIABLE".equals(relationship.type()))
                .filter(relationship -> relationship.startKey().equals(ownerKey))
                .map(ExtractedGraphRelationship::endKey)
                .toList();
        return graph.nodes().stream()
                .filter(candidate -> candidate.labels().contains("CELLML_VARIABLE"))
                .filter(candidate -> variableKeys.contains(candidate.key()))
                .map(candidate -> candidate.properties().get("name"))
                .collect(Collectors.toSet());
    }
}
