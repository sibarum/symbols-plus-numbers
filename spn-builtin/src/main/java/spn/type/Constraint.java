package spn.type;

/**
 * A constraint on values of a user-defined algebraic type.
 *
 * Each variant is a record that holds the constraint parameters and knows how to
 * check a runtime value. Constraints are sealed so the consistency checker (layer 3)
 * can exhaustively analyze them.
 *
 * Design for Truffle performance:
 * Constraint objects are stored in a @CompilationFinal array inside SpnTypeDescriptor.
 * When Graal compiles a SpnCheckConstraintNode, it sees each constraint as a constant,
 * devirtualizes the check() call, and inlines the body. So at steady state,
 * "GreaterThanOrEqual(0).check(value)" compiles down to a single CMP instruction.
 */
public sealed interface Constraint {

    /**
     * Returns true if the value satisfies this constraint.
     * The value is a boxed SPN runtime value (Long, Double, Boolean, String, etc.).
     */
    boolean check(Object value);

    /** Human-readable description for error messages, e.g. "n >= 0". */
    String describe();

    /**
     * Human-readable description using a specific variable name.
     * For numeric constraints, replaces the default "n" with the given name.
     * Non-numeric constraints (string, symbol, etc.) ignore the name and
     * delegate to describe().
     */
    default String describe(String varName) {
        return describe();
    }

    // ── Comparison constraints ──────────────────────────────────────────────

    record GreaterThanOrEqual(double bound) implements Constraint {
        @Override
        public boolean check(Object value) {
            return switch (value) {
                case Long l -> l >= bound;
                case Double d -> d >= bound;
                default -> false;
            };
        }

        @Override
        public String describe() {
            return "n >= " + formatBound(bound);
        }

        @Override
        public String describe(String varName) {
            return varName + " >= " + formatBound(bound);
        }
    }

    record GreaterThan(double bound) implements Constraint {
        @Override
        public boolean check(Object value) {
            return switch (value) {
                case Long l -> l > bound;
                case Double d -> d > bound;
                default -> false;
            };
        }

        @Override
        public String describe() {
            return "n > " + formatBound(bound);
        }

        @Override
        public String describe(String varName) {
            return varName + " > " + formatBound(bound);
        }
    }

    record LessThanOrEqual(double bound) implements Constraint {
        @Override
        public boolean check(Object value) {
            return switch (value) {
                case Long l -> l <= bound;
                case Double d -> d <= bound;
                default -> false;
            };
        }

        @Override
        public String describe() {
            return "n <= " + formatBound(bound);
        }

        @Override
        public String describe(String varName) {
            return varName + " <= " + formatBound(bound);
        }
    }

    record LessThan(double bound) implements Constraint {
        @Override
        public boolean check(Object value) {
            return switch (value) {
                case Long l -> l < bound;
                case Double d -> d < bound;
                default -> false;
            };
        }

        @Override
        public String describe() {
            return "n < " + formatBound(bound);
        }

        @Override
        public String describe(String varName) {
            return varName + " < " + formatBound(bound);
        }
    }

    // ── Modular arithmetic constraints ──────────────────────────────────────

    /**
     * n % divisor == remainder.
     *
     * Common uses:
     *   ModuloEquals(1, 0) → value must be a whole number (n % 1 == 0)
     *   ModuloEquals(2, 0) → value must be even
     *   ModuloEquals(2, 1) → value must be odd
     */
    record ModuloEquals(long divisor, long remainder) implements Constraint {
        @Override
        public boolean check(Object value) {
            return switch (value) {
                case Long l -> l % divisor == remainder;
                case Double d -> d % divisor == remainder;
                default -> false;
            };
        }

        @Override
        public String describe() {
            if (remainder == 0) {
                return "n % " + divisor + " == 0";
            }
            return "n % " + divisor + " == " + remainder;
        }

        @Override
        public String describe(String varName) {
            if (remainder == 0) {
                return varName + " % " + divisor + " == 0";
            }
            return varName + " % " + divisor + " == " + remainder;
        }
    }

    // ── Symbol constraints ─────────────────────────────────────────────────

    /**
     * Value must be a symbol and must be one of the specified allowed symbols.
     * Uses reference equality (==) since symbols are interned.
     *
     * This enables enum-like constrained symbol types:
     * <pre>
     *   var table = new SpnSymbolTable();
     *   var red = table.intern("red");
     *   var green = table.intern("green");
     *   var blue = table.intern("blue");
     *
     *   SpnTypeDescriptor color = SpnTypeDescriptor.builder("Color")
     *       .constraint(Constraint.SymbolOneOf.of(red, green, blue))
     *       .build();
     * </pre>
     */
    record SymbolOneOf(SpnSymbol[] allowed) implements Constraint {

        /** Varargs convenience factory. */
        public static SymbolOneOf of(SpnSymbol... allowed) {
            return new SymbolOneOf(allowed);
        }

        @Override
        public boolean check(Object value) {
            if (!(value instanceof SpnSymbol sym)) return false;
            for (SpnSymbol a : allowed) {
                if (a == sym) return true;
            }
            return false;
        }

        @Override
        public String describe() {
            var sb = new StringBuilder("oneOf(");
            for (int i = 0; i < allowed.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(":").append(allowed[i].name());
            }
            return sb.append(")").toString();
        }
    }

    /**
     * Value must be a SpnSymbol (any symbol at all).
     * Used as a type check rather than a value constraint.
     */
    record IsSymbol() implements Constraint {
        @Override
        public boolean check(Object value) {
            return value instanceof SpnSymbol;
        }

        @Override
        public String describe() {
            return "isSymbol";
        }
    }

    // ── String constraints ──────────────────────────────────────────────────

    /**
     * String must have at least the specified length.
     *
     * <pre>
     *   new Constraint.MinLength(1)  // non-empty string
     * </pre>
     */
    record MinLength(int min) implements Constraint {
        @Override
        public boolean check(Object value) {
            return value instanceof String s && s.length() >= min;
        }

        @Override
        public String describe() {
            return "length >= " + min;
        }
    }

    /**
     * String must have at most the specified length.
     */
    record MaxLength(int max) implements Constraint {
        @Override
        public boolean check(Object value) {
            return value instanceof String s && s.length() <= max;
        }

        @Override
        public String describe() {
            return "length <= " + max;
        }
    }

    /**
     * String must match the given regular expression (full match, not partial).
     *
     * The regex is compiled once at constraint creation time and reused.
     * The pattern is stored as a String for serialization; the compiled Pattern
     * is transient and rebuilt on demand.
     *
     * <pre>
     *   new Constraint.MatchesPattern("[a-zA-Z_][a-zA-Z0-9_]*")  // identifier
     * </pre>
     */
    record MatchesPattern(String regex) implements Constraint {
        @Override
        public boolean check(Object value) {
            return value instanceof String s && s.matches(regex);
        }

        @Override
        public String describe() {
            return "matches /" + regex + "/";
        }
    }

    /**
     * String must consist entirely of characters from the specified character class.
     */
    record CharSetConstraint(CharClass charClass) implements Constraint {
        @Override
        public boolean check(Object value) {
            if (!(value instanceof String s)) return false;
            for (int i = 0; i < s.length(); i++) {
                if (!charClass.accepts(s.charAt(i))) return false;
            }
            return true;
        }

        @Override
        public String describe() {
            return "charset " + charClass.name();
        }
    }

    /** Predefined character classes for CharSetConstraint. */
    enum CharClass {
        ASCII {
            @Override public boolean accepts(char c) { return c < 128; }
        },
        ASCII_PRINTABLE {
            @Override public boolean accepts(char c) { return c >= 32 && c < 127; }
        },
        ALPHANUMERIC {
            @Override public boolean accepts(char c) {
                return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            }
        },
        ALPHA {
            @Override public boolean accepts(char c) {
                return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            }
        },
        DIGIT {
            @Override public boolean accepts(char c) { return c >= '0' && c <= '9'; }
        },
        HEX {
            @Override public boolean accepts(char c) {
                return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            }
        };

        public abstract boolean accepts(char c);
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    private static String formatBound(double bound) {
        // Print "0" instead of "0.0" when the bound is a whole number
        if (bound == Math.floor(bound) && !Double.isInfinite(bound)) {
            return Long.toString((long) bound);
        }
        return Double.toString(bound);
    }
}
