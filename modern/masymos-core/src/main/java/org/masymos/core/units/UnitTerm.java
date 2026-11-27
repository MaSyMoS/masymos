package org.masymos.core.units;

import java.util.Objects;

/**
 * One term of a declared unit: the referenced unit, its exponent, and the multiplier already
 * applied to that reference (a CellML prefix, an SBML {@code 10^scale}, or a declared multiplier).
 *
 * <p>All three values are exact decimal strings; the algebra never goes through {@code double}.
 */
public record UnitTerm(String unit, String exponent, String multiplier) {
    public UnitTerm {
        Objects.requireNonNull(unit, "unit");
        exponent = exponent == null || exponent.isBlank() ? "1" : exponent;
        multiplier = multiplier == null || multiplier.isBlank() ? "1" : multiplier;
    }
}
