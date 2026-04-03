package spn.type;

import spn.node.match.MatchPattern;

/**
 * A finite set of symbols representing all valid values of a constrained symbol type.
 *
 * Analogous to SpnVariantSet (which groups struct variants), SpnSymbolSet groups
 * the symbols that a constrained type allows. The totality checker uses this to
 * verify that pattern matching over a symbol value is exhaustive.
 *
 * <pre>
 *   var table = new SpnSymbolTable();
 *   var red   = table.intern("red");
 *   var green = table.intern("green");
 *   var blue  = table.intern("blue");
 *
 *   var COLOR = new SpnSymbolSet("Color", red, green, blue);
 *
 *   // Totality checker verifies:
 *   //   match color { :red -> ..., :green -> ..., :blue -> ... }
 *   // covers all variants of Color
 * </pre>
 */
public final class SpnSymbolSet {

    private final String name;
    private final SpnSymbol[] symbols;

    public SpnSymbolSet(String name, SpnSymbol... symbols) {
        this.name = name;
        this.symbols = symbols;
    }

    public String getName() {
        return name;
    }

    public SpnSymbol[] getSymbols() {
        return symbols;
    }

    public int size() {
        return symbols.length;
    }

    /**
     * Checks whether the given set of match patterns covers every symbol in this set.
     * A Wildcard pattern covers everything. Literal patterns with SpnSymbol values
     * cover specific symbols.
     */
    public boolean isCoveredBy(MatchPattern[] patterns) {
        for (MatchPattern p : patterns) {
            if (p instanceof MatchPattern.Wildcard) return true;
        }

        outer:
        for (SpnSymbol sym : symbols) {
            for (MatchPattern p : patterns) {
                if (p instanceof MatchPattern.Literal lit && lit.expected() == sym) {
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Returns the symbols not covered by the given patterns.
     */
    public SpnSymbol[] uncoveredSymbols(MatchPattern[] patterns) {
        for (MatchPattern p : patterns) {
            if (p instanceof MatchPattern.Wildcard) return new SpnSymbol[0];
        }

        var missing = new java.util.ArrayList<SpnSymbol>();
        outer:
        for (SpnSymbol sym : symbols) {
            for (MatchPattern p : patterns) {
                if (p instanceof MatchPattern.Literal lit && lit.expected() == sym) {
                    continue outer;
                }
            }
            missing.add(sym);
        }
        return missing.toArray(new SpnSymbol[0]);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder(name).append(" = ");
        for (int i = 0; i < symbols.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(":").append(symbols[i].name());
        }
        return sb.toString();
    }
}
