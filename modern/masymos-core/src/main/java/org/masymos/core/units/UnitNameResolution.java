package org.masymos.core.units;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves what a user types into unit keys: an exact name first, then a prefix applied to a known
 * unit ({@code cm}, {@code km}, {@code um}, {@code µm}, {@code mmol}, {@code mL}).
 *
 * <p>Exact matches always win over decomposition, an unknown name resolves to nothing rather than
 * a guess, and a decomposition that has several readings returns all of them (design 4.1/4.4).
 */
public final class UnitNameResolution {

    private record Prefix(String spelling, BigDecimal scale) {}

    /** SI symbols and the spellings neither specification registers but every user writes. */
    private static final Map<String, String> SYMBOLS = symbols();

    /**
     * Well-defined derived names the specifications do not list as built-ins. {@code molar} is
     * {@code mole/litre} = 10^3 mole metre^-3; without it {@code nanomolar} cannot resolve.
     */
    private static final Map<String, UnitCatalogue.Entry> DERIVED_NAMES = derivedNames();

    private static final List<Prefix> PREFIXES = prefixes();

    private final UnitCatalogue catalogue;

    public UnitNameResolution(UnitCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    /** Every key this input can mean, or an empty list when nothing resolves. */
    public List<String> keysFor(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        var name = input.trim();
        var exact = entry(name);
        if (exact.isPresent()) {
            return List.of(exact.get().key());
        }

        var keys = new ArrayList<String>();
        for (var prefix : PREFIXES) {
            var spelling = prefix.spelling();
            if (!name.startsWith(spelling) || name.length() == spelling.length()) {
                continue;
            }
            var rest = name.substring(spelling.length());
            var key = entry(rest)
                    .map(base -> UnitCatalogue.derivedKey(
                            prefix.scale().multiply(base.multiplier()), base.bases()));
            if (key.isPresent() && !keys.contains(key.get())) {
                keys.add(key.get());
            }
        }
        return List.copyOf(keys);
    }

    private Optional<UnitCatalogue.Entry> entry(String name) {
        var alias = SYMBOLS.get(name);
        if (alias != null) {
            var resolved = catalogueEntry(alias);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        var direct = catalogueEntry(name);
        if (direct.isPresent()) {
            return direct;
        }
        return Optional.ofNullable(DERIVED_NAMES.get(name));
    }

    private Optional<UnitCatalogue.Entry> catalogueEntry(String name) {
        for (var dialect : UnitDialect.values()) {
            var entry = catalogue.entry(name, dialect);
            if (entry.isPresent()) {
                return entry;
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> symbols() {
        var symbols = new LinkedHashMap<String, String>();
        symbols.put("m", "metre");
        symbols.put("metre", "metre");
        symbols.put("meter", "metre");
        symbols.put("s", "second");
        symbols.put("sec", "second");
        symbols.put("A", "ampere");
        symbols.put("K", "kelvin");
        symbols.put("mol", "mole");
        symbols.put("cd", "candela");
        symbols.put("kg", "kilogram");
        symbols.put("g", "gram");
        symbols.put("L", "litre");
        symbols.put("l", "litre");
        symbols.put("N", "newton");
        symbols.put("J", "joule");
        symbols.put("W", "watt");
        symbols.put("Pa", "pascal");
        symbols.put("Hz", "hertz");
        symbols.put("C", "coulomb");
        symbols.put("V", "volt");
        symbols.put("F", "farad");
        symbols.put("ohm", "ohm");
        symbols.put("S", "siemens");
        symbols.put("Wb", "weber");
        symbols.put("T", "tesla");
        symbols.put("H", "henry");
        symbols.put("lm", "lumen");
        symbols.put("lx", "lux");
        symbols.put("Bq", "becquerel");
        symbols.put("Gy", "gray");
        symbols.put("Sv", "sievert");
        symbols.put("kat", "katal");
        symbols.put("rad", "radian");
        symbols.put("sr", "steradian");
        return Map.copyOf(symbols);
    }

    private static Map<String, UnitCatalogue.Entry> derivedNames() {
        var names = new LinkedHashMap<String, UnitCatalogue.Entry>();
        names.put(
                "molar",
                new UnitCatalogue.Entry(
                        "molar",
                        "derived",
                        Map.of("mole", BigDecimal.ONE, "metre", BigDecimal.valueOf(-3)),
                        BigDecimal.valueOf(1000),
                        null,
                        List.of("molar")));
        return Map.copyOf(names);
    }

    private static List<Prefix> prefixes() {
        var prefixes = new ArrayList<Prefix>();
        for (var spelling : UnitCatalogue.prefixSpellings()) {
            prefixes.add(new Prefix(spelling, UnitCatalogue.prefixScale(spelling).orElseThrow()));
        }
        prefixes.sort(Comparator.comparingInt((Prefix prefix) -> prefix.spelling().length())
                .reversed()
                .thenComparing(Prefix::spelling));
        return List.copyOf(prefixes);
    }
}
