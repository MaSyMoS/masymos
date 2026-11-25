package org.masymos.core.units;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reduces a declared unit to its {@link CanonicalUnit}: flatten the terms into base exponents and
 * one multiplier, and keep the named terms as the definition graph.
 *
 * <p>The flattening is the normative unit reduction shared by CellML 2.0 (section 3.3.3) and the
 * SBML equation 4.1/4.2: multiply exponents, multiply multipliers, sum exponents per base, drop
 * zero exponents and {@code dimensionless}.
 */
public final class UnitResolver {

    private final UnitCatalogue catalogue;

    public UnitResolver(UnitCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    public CanonicalUnit resolve(UnitExpression expression, UnitDialect dialect) {
        return resolve(expression, dialect, Map.of());
    }

    /**
     * Resolves a declaration whose terms may name units the model itself declares. A declared unit
     * is flattened from its own {@link CanonicalUnit#composition()}, so one definition reaches one
     * key however the model spelled it.
     */
    public CanonicalUnit resolve(
            UnitExpression expression, UnitDialect dialect, Map<String, CanonicalUnit> declared) {
        var multiplier = decimal(expression.multiplier());
        var bases = new TreeMap<String, BigDecimal>();
        var components = new ArrayList<UnitComponent>();

        for (var term : expression.terms()) {
            var exponent = decimal(term.exponent());
            var declaredUnit = declared.get(term.unit());
            CanonicalUnit.Composition composition;
            String key;
            if (declaredUnit != null) {
                composition = declaredUnit.composition();
                key = declaredUnit.key();
            } else {
                var entry = catalogue
                        .entry(term.unit(), dialect)
                        .orElseThrow(() -> new IllegalArgumentException("unknown unit: " + term.unit()));
                composition = new CanonicalUnit.Composition(entry.multiplier(), entry.bases());
                key = entry.key();
            }
            multiplier = multiplier
                    .multiply(power(decimal(term.multiplier()), exponent))
                    .multiply(power(composition.multiplier(), exponent));
            for (var base : composition.bases().entrySet()) {
                bases.merge(base.getKey(), base.getValue().multiply(exponent), BigDecimal::add);
            }
            components.add(new UnitComponent(
                    key, UnitCatalogue.normalise(exponent), UnitCatalogue.normalise(decimal(term.multiplier()))));
        }

        var offset = offset(expression.offset());
        return new CanonicalUnit(
                UnitCatalogue.derivedKey(multiplier, bases, offset),
                expression.name(),
                List.of(expression.name()),
                "derived",
                UnitCatalogue.dimensionOf(bases),
                UnitCatalogue.normalise(multiplier),
                offset,
                List.copyOf(components));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static String offset(String value) {
        var offset = decimal(value);
        return offset.signum() == 0 ? null : UnitCatalogue.normalise(offset);
    }

    /**
     * {@code base} raised to an integer exponent. A fractional exponent is rejected rather than
     * rounded: no format in scope defines one, and rounding would silently merge identities.
     */
    static BigDecimal power(BigDecimal base, BigDecimal exponent) {
        int power;
        try {
            power = exponent.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException notAnInteger) {
            throw new IllegalArgumentException(
                    "fractional unit exponents are not supported: " + exponent.toPlainString(), notAnInteger);
        }
        if (power >= 0) {
            return base.pow(power);
        }
        return BigDecimal.ONE.divide(base.pow(-power), MathContext.DECIMAL128);
    }
}
