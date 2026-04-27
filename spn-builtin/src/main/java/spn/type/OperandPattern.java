package spn.type;

/**
 * A pattern that matches against an operand value in an algebraic rule.
 *
 * When an algebraic rule is checked, each operand of the operation is tested against
 * the rule's left and right patterns. If both patterns match, the rule fires and
 * produces its result instead of the normal operation.
 *
 * Patterns are sealed so the consistency checker (layer 3) can exhaustively analyze
 * which values a rule could match.
 *
 * Example rule:  n / 0 = Omega
 *   left pattern:  Any()             -- matches any left operand
 *   right pattern: ExactLong(0)      -- matches only the value 0
 *   result:        omega element
 *
 * Example rule:  Omega + n = Omega
 *   left pattern:  IsElement("Omega") -- matches only the Omega element
 *   right pattern: Any()              -- matches any right operand
 *   result:        omega element
 */
public sealed interface OperandPattern {

    /** Returns true if the runtime value matches this pattern. */
    boolean matches(Object value);

    /** Human-readable description for error messages and debugging. */
    String describe();

    // ── Pattern variants ────────────────────────────────────────────────────

    /** Matches any value whatsoever. */
    record Any() implements OperandPattern {
        @Override
        public boolean matches(Object value) {
            return true;
        }

        @Override
        public String describe() {
            return "n";
        }
    }

    /** Matches a specific long value (e.g., 0 in "n / 0 = Omega"). */
    record ExactLong(long expected) implements OperandPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof Long l && l == expected;
        }

        @Override
        public String describe() {
            return Long.toString(expected);
        }
    }

    /** Matches a specific double value. */
    record ExactDouble(double expected) implements OperandPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof Double d && d == expected;
        }

        @Override
        public String describe() {
            return Double.toString(expected);
        }
    }

    /**
     * Matches a specific distinguished element by reference identity.
     *
     * Uses reference equality (==) because two elements with the same name from
     * different types are NOT interchangeable.
     */
    record IsElement(SpnDistinguishedElement element) implements OperandPattern {
        @Override
        public boolean matches(Object value) {
            return value == element;
        }

        @Override
        public String describe() {
            return element.getName();
        }
    }

    /** Matches any numeric value (Long or Double, but not distinguished elements). */
    record AnyNumber() implements OperandPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof Long || value instanceof Double;
        }

        @Override
        public String describe() {
            return "num";
        }
    }
}
