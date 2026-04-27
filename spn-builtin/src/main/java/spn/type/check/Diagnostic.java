package spn.type.check;

/**
 * A diagnostic message produced by the TypeConsistencyChecker.
 *
 * Diagnostics come in three severities:
 *   ERROR   - The type definition is self-contradictory. No valid values can exist,
 *             or a rule produces impossible results. The type should not be used.
 *   WARNING - The type definition is technically valid but likely contains a mistake.
 *             For example, a rule is unreachable because an earlier rule shadows it,
 *             or a distinguished element has no rules covering any operation on it.
 *   INFO    - Informational. Describes what the type represents (e.g., the feasible
 *             value range) to help the user verify their intent.
 */
public record Diagnostic(Severity severity, Category category, String message) {

    public enum Severity {
        ERROR, WARNING, INFO
    }

    public enum Category {
        /** Constraints are mutually contradictory -- no value can satisfy all of them. */
        EMPTY_TYPE,

        /** Two rules can match the same input but produce different results. */
        RULE_CONFLICT,

        /** A rule produces a value that violates the type's constraints. */
        RULE_OUTPUT_VIOLATION,

        /** A rule is completely shadowed by an earlier rule and will never fire. */
        UNREACHABLE_RULE,

        /** A distinguished element has no rules, so any operation on it will fail. */
        ELEMENT_COVERAGE,

        /** Informational description of the type's feasible value set. */
        FEASIBILITY
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + category + ": " + message;
    }

    /** Convenience factory for ERROR diagnostics. */
    public static Diagnostic error(Category category, String message) {
        return new Diagnostic(Severity.ERROR, category, message);
    }

    /** Convenience factory for WARNING diagnostics. */
    public static Diagnostic warning(Category category, String message) {
        return new Diagnostic(Severity.WARNING, category, message);
    }

    /** Convenience factory for INFO diagnostics. */
    public static Diagnostic info(Category category, String message) {
        return new Diagnostic(Severity.INFO, category, message);
    }
}
