package org.masymos.core.units;

import java.util.List;
import java.util.Objects;

/**
 * A unit as a format declares it: a name, the product of its terms, and an optional offset.
 *
 * <p>This is the format-neutral input of {@link UnitResolver}; the parsers build it from CellML
 * {@code units} elements and SBML {@code UnitDefinition}s.
 */
public record UnitExpression(String name, List<UnitTerm> terms, String multiplier, String offset) {
    public UnitExpression {
        Objects.requireNonNull(name, "name");
        terms = List.copyOf(terms);
        multiplier = multiplier == null || multiplier.isBlank() ? "1" : multiplier;
        offset = offset == null || offset.isBlank() ? "0" : offset;
    }
}
