package org.masymos.core.units;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The built-in unit vocabulary of the supported formats, reduced to irreducible base units.
 *
 * <p>The tables are the normative ones (design section 2): CellML 2.0 table 3.1 and the SBML
 * base-unit table. SBML calls its 33 kinds "base units", but only the seven SI units are
 * irreducible; every other kind is a reduction through them. CellML 1.0/1.1 additionally accept
 * the American spellings and {@code celsius}, and SBML Level 1 knows neither {@code avogadro}
 * nor {@code item}.
 */
public final class UnitCatalogue {

    /**
     * One row of a built-in table: the canonical name, whether it is irreducible, its base
     * composition, its multiplier and offset, and every spelling the table accepts for it.
     */
    record Entry(
            String name,
            String kind,
            Map<String, BigDecimal> bases,
            BigDecimal multiplier,
            String offset,
            List<String> names) {

        boolean irreducible() {
            return "base".equals(kind);
        }

        String key() {
            return irreducible() ? "base:" + name : derivedKey(multiplier, bases, offset);
        }

        Entry withNames(List<String> spellings) {
            return new Entry(name, kind, bases, multiplier, offset, List.copyOf(spellings));
        }
    }

    private static final Entry METRE = base("metre");
    private static final Entry DIMENSIONLESS = base("dimensionless");
    private static final Entry KELVIN = base("kelvin");

    private static final List<Entry> BASES = List.of(
            METRE,
            base("kilogram"),
            base("second"),
            base("ampere"),
            KELVIN,
            base("mole"),
            base("candela"),
            DIMENSIONLESS);

    /** CellML 2.0 table 3.1 and the SBML kinds: one table, because the reductions agree. */
    private static final Map<String, Entry> DERIVED = derived();

    private static final Entry CELSIUS = derived("celsius", "1", Map.of("kelvin", bd(1)), "273.15");
    private static final Entry AVOGADRO = derived("avogadro", "6.02214179e23", Map.of());

    /** CellML's prefix table, by name and by symbol. */
    private static final Map<String, BigDecimal> PREFIXES = prefixes();

    private static final Map<UnitDialect, Map<String, Entry>> TABLES = tables();

    private final Map<UnitDialect, Map<String, Entry>> tables;

    private UnitCatalogue(Map<UnitDialect, Map<String, Entry>> tables) {
        this.tables = tables;
    }

    /** The catalogue of every built-in the supported formats define. */
    public static UnitCatalogue builtIns() {
        return new UnitCatalogue(TABLES);
    }

    /** The table row a dialect gives this name, if it has one. */
    Optional<Entry> entry(String unit, UnitDialect dialect) {
        var table = tables.getOrDefault(dialect, Map.of());
        var direct = table.get(unit);
        if (direct != null) {
            return Optional.of(direct);
        }
        if (dialect == UnitDialect.SBML_1) {
            return Optional.ofNullable(table.get(unit.toLowerCase(Locale.ROOT)));
        }
        return Optional.empty();
    }

    /** The canonical form of a built-in unit, or empty when the dialect does not know the name. */
    public Optional<CanonicalUnit> builtIn(String unit, UnitDialect dialect) {
        return entry(unit, dialect).map(this::canonical);
    }

    /** Every spelling a dialect accepts, for diagnostics and the filter's exact step. */
    public Set<String> names(UnitDialect dialect) {
        return Set.copyOf(tables.getOrDefault(dialect, Map.of()).keySet());
    }

    /**
     * The canonical unit a dialect gives this key, if a built-in has it. The unit conversion needs
     * this for the closure of a composed unit ({@code litre} reduces to {@code base:metre}).
     */
    public Optional<CanonicalUnit> byKey(String key, UnitDialect dialect) {
        return tables.getOrDefault(dialect, Map.of()).values().stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst()
                .map(this::canonical);
    }

    /** The CellML prefix table (yotta to yocto), by name and by symbol, with {@code µ} and {@code u}. */
    public static Optional<BigDecimal> prefixScale(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        var scale = PREFIXES.get(prefix);
        if (scale != null) {
            return Optional.of(scale);
        }
        if (prefix.matches("-?\\d+")) {
            var exponent = Integer.parseInt(prefix);
            return Optional.of(exponent >= 0
                    ? BigDecimal.TEN.pow(exponent)
                    : BigDecimal.ONE.divide(BigDecimal.TEN.pow(-exponent), MathContext.DECIMAL128));
        }
        return Optional.empty();
    }

    /** Every prefix spelling, so a resolver can try the longest one first. */
    public static Set<String> prefixSpellings() {
        return PREFIXES.keySet();
    }

    private CanonicalUnit canonical(Entry entry) {
        List<UnitComponent> components = entry.irreducible()
                ? List.of()
                : entry.bases().entrySet().stream()
                        .map(base -> new UnitComponent("base:" + base.getKey(), normalise(base.getValue()), "1"))
                        .toList();
        return new CanonicalUnit(
                entry.key(),
                entry.name(),
                entry.names(),
                entry.kind(),
                entry.irreducible() ? dimensionOfBase(entry.name()) : dimensionOf(entry.bases()),
                normalise(entry.multiplier()),
                entry.offset(),
                components);
    }

    /** A decimal in the one spelling identities use: {@code 1e-9} and {@code 0.000000001} agree. */
    public static String normalise(String decimal) {
        return normalise(new BigDecimal(decimal));
    }

    public static String normalise(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** The sorted base exponents without the multiplier, e.g. {@code metre^-3*mole^1}. */
    static String dimensionOf(Map<String, BigDecimal> bases) {
        var sorted = new TreeMap<>(bases);
        sorted.entrySet().removeIf(base -> base.getValue().signum() == 0);
        sorted.remove("dimensionless");
        if (sorted.isEmpty()) {
            return "dimensionless";
        }
        return sorted.entrySet().stream()
                .map(base -> base.getKey() + "^" + normalise(base.getValue()))
                .collect(Collectors.joining("*"));
    }

    static String derivedKey(BigDecimal multiplier, Map<String, BigDecimal> bases) {
        return derivedKey(multiplier, bases, null);
    }

    /**
     * The identity of a composed unit: multiplier and sorted base exponents, plus the offset when
     * there is one (design 4.1). Locations go through {@code BigDecimal} only.
     */
    static String derivedKey(BigDecimal multiplier, Map<String, BigDecimal> bases, String offset) {
        var key = "unit:" + normalise(multiplier) + "*" + dimensionOf(bases);
        return offset == null || offset.isBlank() ? key : key + "+" + offset;
    }

    private static String dimensionOfBase(String name) {
        return "dimensionless".equals(name) ? "dimensionless" : name + "^1";
    }

    private static Entry base(String name) {
        return new Entry(name, "base", Map.of(name, BigDecimal.ONE), BigDecimal.ONE, null, List.of(name));
    }

    private static Entry derived(String name, String multiplier, Map<String, BigDecimal> bases) {
        return derived(name, multiplier, bases, null);
    }

    private static Entry derived(String name, String multiplier, Map<String, BigDecimal> bases, String offset) {
        return new Entry(name, "derived", bases, new BigDecimal(multiplier), offset, List.of(name));
    }

    private static BigDecimal bd(int value) {
        return BigDecimal.valueOf(value);
    }

    private static Map<String, Entry> derived() {
        var table = new LinkedHashMap<String, Entry>();
        table.put("becquerel", derived("becquerel", "1", Map.of("second", bd(-1))));
        table.put("coulomb", derived("coulomb", "1", Map.of("ampere", bd(1), "second", bd(1))));
        table.put("farad", derived("farad", "1", Map.of("kilogram", bd(-1), "metre", bd(-2), "second", bd(4), "ampere", bd(2))));
        table.put("gram", derived("gram", "0.001", Map.of("kilogram", bd(1))));
        table.put("gray", derived("gray", "1", Map.of("metre", bd(2), "second", bd(-2))));
        table.put("henry", derived("henry", "1", Map.of("kilogram", bd(1), "metre", bd(2), "second", bd(-2), "ampere", bd(-2))));
        table.put("hertz", derived("hertz", "1", Map.of("second", bd(-1))));
        table.put("joule", derived("joule", "1", Map.of("kilogram", bd(1), "metre", bd(2), "second", bd(-2))));
        table.put("katal", derived("katal", "1", Map.of("mole", bd(1), "second", bd(-1))));
        table.put("litre", derived("litre", "0.001", Map.of("metre", bd(3))));
        table.put("lumen", derived("lumen", "1", Map.of("candela", bd(1))));
        table.put("lux", derived("lux", "1", Map.of("candela", bd(1), "metre", bd(-2))));
        table.put("newton", derived("newton", "1", Map.of("kilogram", bd(1), "metre", bd(1), "second", bd(-2))));
        table.put("ohm", derived("ohm", "1", Map.of("kilogram", bd(1), "metre", bd(2), "second", bd(-3), "ampere", bd(-2))));
        table.put("pascal", derived("pascal", "1", Map.of("kilogram", bd(1), "metre", bd(-1), "second", bd(-2))));
        table.put("radian", derived("radian", "1", Map.of()));
        table.put("siemens", derived("siemens", "1", Map.of("kilogram", bd(-1), "metre", bd(-2), "second", bd(3), "ampere", bd(2))));
        table.put("sievert", derived("sievert", "1", Map.of("metre", bd(2), "second", bd(-2))));
        table.put("steradian", derived("steradian", "1", Map.of()));
        table.put("tesla", derived("tesla", "1", Map.of("kilogram", bd(1), "second", bd(-2), "ampere", bd(-1))));
        table.put("volt", derived("volt", "1", Map.of("kilogram", bd(1), "metre", bd(2), "second", bd(-3), "ampere", bd(-1))));
        table.put("watt", derived("watt", "1", Map.of("kilogram", bd(1), "metre", bd(2), "second", bd(-3))));
        table.put("weber", derived("weber", "1", Map.of("kilogram", bd(1), "metre", bd(2), "second", bd(-2), "ampere", bd(-1))));
        return Map.copyOf(table);
    }

    private static Map<UnitDialect, Map<String, Entry>> tables() {
        var cellml20 = new LinkedHashMap<String, Entry>();
        BASES.forEach(entry -> register(cellml20, entry, entry.name()));
        DERIVED.values().forEach(entry -> register(cellml20, entry, entry.name()));

        var cellml11 = new LinkedHashMap<>(cellml20);
        register(cellml11, METRE, "metre", "meter");
        register(cellml11, DERIVED.get("litre"), "litre", "liter");
        register(cellml11, CELSIUS, "celsius");

        var sbml3 = new LinkedHashMap<>(cellml20);
        register(sbml3, DIMENSIONLESS, "dimensionless", "item");
        register(sbml3, AVOGADRO, "avogadro");

        var sbml2 = new LinkedHashMap<>(sbml3);
        sbml2.remove("avogadro");
        // L2V1 is the one SBML level that had `Celsius` and `Unit/@offset`; L2V2 removed both. The
        // dialect granularity is per level, so L2 accepts the name and the parser reads the offset
        // only from L2V1 documents.
        register(sbml2, CELSIUS, "celsius", "Celsius");

        var sbml1 = new LinkedHashMap<String, Entry>();
        BASES.forEach(entry -> register(sbml1, entry, entry.name()));
        DERIVED.values().forEach(entry -> register(sbml1, entry, entry.name()));
        sbml1.remove("metre");
        sbml1.remove("litre");
        register(sbml1, METRE, "meter", "metre");
        register(sbml1, DERIVED.get("litre"), "liter", "litre");
        register(sbml1, CELSIUS, "celsius");

        var tables = new LinkedHashMap<UnitDialect, Map<String, Entry>>();
        tables.put(UnitDialect.CELLML_10_11, Map.copyOf(cellml11));
        tables.put(UnitDialect.CELLML_20, Map.copyOf(cellml20));
        tables.put(UnitDialect.SBML_1, Map.copyOf(sbml1));
        tables.put(UnitDialect.SBML_2, Map.copyOf(sbml2));
        tables.put(UnitDialect.SBML_3, Map.copyOf(sbml3));
        return Map.copyOf(tables);
    }

    private static Map<String, BigDecimal> prefixes() {
        var table = new String[][] {
            {"yotta", "Y", "1e24"},
            {"zetta", "Z", "1e21"},
            {"exa", "E", "1e18"},
            {"peta", "P", "1e15"},
            {"tera", "T", "1e12"},
            {"giga", "G", "1e9"},
            {"mega", "M", "1e6"},
            {"kilo", "k", "1e3"},
            {"hecto", "h", "1e2"},
            {"deca", "da", "1e1"},
            {"deci", "d", "1e-1"},
            {"centi", "c", "1e-2"},
            {"milli", "m", "1e-3"},
            {"micro", "µ", "1e-6"},
            {"micro", "μ", "1e-6"},
            {"micro", "u", "1e-6"},
            {"nano", "n", "1e-9"},
            {"pico", "p", "1e-12"},
            {"femto", "f", "1e-15"},
            {"atto", "a", "1e-18"},
            {"zepto", "z", "1e-21"},
            {"yocto", "y", "1e-24"},
        };
        var prefixes = new LinkedHashMap<String, BigDecimal>();
        for (var row : table) {
            var scale = new BigDecimal(row[2]);
            prefixes.put(row[0], scale);
            prefixes.put(row[1], scale);
        }
        return Map.copyOf(prefixes);
    }

    private static void register(Map<String, Entry> table, Entry entry, String... spellings) {
        var names = new ArrayList<String>();
        names.add(entry.name());
        for (var spelling : spellings) {
            if (!names.contains(spelling)) {
                names.add(spelling);
            }
        }
        var named = entry.withNames(names);
        for (var spelling : spellings) {
            table.put(spelling, named);
        }
    }
}
