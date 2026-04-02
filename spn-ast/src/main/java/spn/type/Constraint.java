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
