package org.masymos.core.extract;

import java.util.List;
import java.util.Objects;
import org.masymos.core.units.CanonicalUnit;

/**
 * Resolves the content of a CellML {@code <import>} target so imported components can carry their
 * variables instead of staying an empty shell, and so imported {@code <units>} declarations become
 * real units of the importing document (CML-1).
 *
 * <p><b>Import resolution stays opt-in (CML-2 decision, 2026-11-13).</b> {@link CellmlModelParser}'s
 * no-argument constructor and its {@link CellmlExternalCmetaResolver} constructor both use
 * {@link #NONE}, for the same reason external cmeta is opt-in: an index run must stay reproducible
 * from its inputs, and the default parse must not read anything outside the document it was given.
 * A caller that has the import targets at hand (a fixture directory, an extracted archive) passes
 * {@link FileCellmlImportResolver} explicitly.
 *
 * <p>A resolver never fails the model. When it cannot resolve an href it returns
 * {@link Unresolved} with a reason from the closed vocabulary {@code not-found}, {@code unreadable},
 * {@code too-large}, {@code parse-failed}, {@code component-missing} or {@code units-missing}; the
 * parser writes that reason onto the entities so an unresolved import is visible rather than silent.
 * A declaration the target has but that cannot be reduced under the target's own names reports
 * {@code units-missing} as well: the target offers no usable unit of that reference. The parser adds
 * one reason of its own, {@code no-imported-components}, for an import whose components resolved
 * nothing.
 */
@FunctionalInterface
public interface CellmlImportResolver {
    /** The default: resolves nothing. Keep this the default; see the type javadoc. */
    CellmlImportResolver NONE = (href, componentRef, source) -> new Unresolved("no-resolver");

    /**
     * Resolves one imported component.
     *
     * @param href the import's {@code xlink:href}, never null
     * @param componentRef the {@code component_ref} of the imported component, never blank
     * @param source the model being parsed, for callers that need its location
     * @return {@link Resolved} when the target component could be read, otherwise {@link Unresolved}
     */
    ImportTarget resolve(String href, String componentRef, ModelSource source);

    /**
     * Resolves one {@code <import><units>} declaration (CML-1).
     *
     * <p>The default resolves nothing, so an implementation written before units existed keeps its
     * behaviour and the parser records {@code no-resolver} instead of guessing. Declared as a
     * default method on purpose: this SPI stays a functional interface for the callers that pass a
     * lambda.
     *
     * @param href the import's {@code xlink:href}, never null
     * @param unitsRef the {@code units_ref} of the imported units declaration, never blank
     * @param source the model being parsed, for callers that need its location
     * @return {@link ResolvedUnits} when the target declares the unit, otherwise {@link Unresolved}
     */
    default UnitsTarget resolveUnits(String href, String unitsRef, ModelSource source) {
        return new Unresolved("no-resolver");
    }

    /** Outcome of one import resolution: either the target component or an explicit reason. */
    sealed interface ImportTarget permits Resolved, Unresolved {
    }

    /** Outcome of one imported-units resolution: either the unit or an explicit reason. */
    sealed interface UnitsTarget permits ResolvedUnits, Unresolved {
    }

    /**
     * The target component was found.
     *
     * @param componentName the {@code name} attribute of the target {@code <component>}, may be blank
     * @param cmetaId the component's cmeta id, empty when it has none
     * @param variables the component's variables in document order
     */
    record Resolved(String componentName, String cmetaId, List<ImportVariable> variables)
            implements ImportTarget {
        public Resolved {
            componentName = componentName == null ? "" : componentName;
            cmetaId = cmetaId == null ? "" : cmetaId;
            variables = variables == null ? List.of() : List.copyOf(variables);
        }
    }

    /**
     * The target document declares the unit.
     *
     * @param unitsRef the {@code units_ref} as written in the importing document
     * @param unit the declaration reduced in the target document's own scope, so an imported metre
     *     and a local metre share one key
     */
    record ResolvedUnits(String unitsRef, CanonicalUnit unit) implements UnitsTarget {
        public ResolvedUnits {
            unitsRef = unitsRef == null ? "" : unitsRef;
            Objects.requireNonNull(unit, "unit");
        }
    }

    /** The target could not be resolved; {@code reason} is the closed-vocabulary reason. */
    record Unresolved(String reason) implements ImportTarget, UnitsTarget {
    }

    /** One {@code <variable>} of the resolved component; interface attributes are empty when absent. */
    record ImportVariable(String name, String publicInterface, String privateInterface, String units) {
        public ImportVariable {
            name = name == null ? "" : name;
            publicInterface = publicInterface == null ? "" : publicInterface;
            privateInterface = privateInterface == null ? "" : privateInterface;
            units = units == null ? "" : units;
        }
    }
}
