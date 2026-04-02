package spn.type.check;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.type.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static spn.type.check.Diagnostic.Category.*;
import static spn.type.check.Diagnostic.Severity.*;

class TypeConsistencyCheckerTest {

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean hasError(List<Diagnostic> diagnostics, Diagnostic.Category category) {
        return diagnostics.stream()
                .anyMatch(d -> d.severity() == ERROR && d.category() == category);
    }

    private static boolean hasWarning(List<Diagnostic> diagnostics, Diagnostic.Category category) {
        return diagnostics.stream()
                .anyMatch(d -> d.severity() == WARNING && d.category() == category);
    }

    private static boolean hasInfo(List<Diagnostic> diagnostics, Diagnostic.Category category) {
        return diagnostics.stream()
                .anyMatch(d -> d.severity() == INFO && d.category() == category);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. CONSTRAINT SATISFIABILITY
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ConstraintSatisfiability {

        @Test
        void validNaturalNumbers() {
            var type = new SpnTypeDescriptor("Natural",
                    new Constraint.GreaterThanOrEqual(0),
                    new Constraint.ModuloEquals(1, 0));

            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(TypeConsistencyChecker.hasErrors(diagnostics));
            assertTrue(hasInfo(diagnostics, FEASIBILITY));
        }

        @Test
        void contradictoryBounds() {
            // n >= 5 AND n < 3 → impossible
            var type = new SpnTypeDescriptor("Bad",
                    new Constraint.GreaterThanOrEqual(5),
                    new Constraint.LessThan(3));

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasError(diagnostics, EMPTY_TYPE));
        }

        @Test
        void exclusiveBoundsAtSamePoint() {
            // n > 5 AND n < 5 → impossible
            var type = new SpnTypeDescriptor("Bad",
                    new Constraint.GreaterThan(5),
                    new Constraint.LessThan(5));

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasError(diagnostics, EMPTY_TYPE));
        }

        @Test
        void singlePointInclusive() {
            // n >= 5 AND n <= 5 → only value is 5
            var type = new SpnTypeDescriptor("Five",
                    new Constraint.GreaterThanOrEqual(5),
                    new Constraint.LessThanOrEqual(5));

            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(TypeConsistencyChecker.hasErrors(diagnostics));
        }

        @Test
        void singlePointExclusiveOnOneSide() {
            // n > 5 AND n <= 5 → impossible
            var type = new SpnTypeDescriptor("Bad",
                    new Constraint.GreaterThan(5),
                    new Constraint.LessThanOrEqual(5));

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasError(diagnostics, EMPTY_TYPE));
        }

        @Test
        void incompatibleModuloConstraints() {
            // n % 2 == 0 AND n % 2 == 1 → impossible (can't be both even and odd)
            var type = new SpnTypeDescriptor("Bad",
                    new Constraint.ModuloEquals(2, 0),
                    new Constraint.ModuloEquals(2, 1));

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasError(diagnostics, EMPTY_TYPE));
        }

        @Test
        void compatibleModuloConstraints() {
            // n % 6 == 1 AND n % 2 == 1 → compatible (e.g., 1, 7, 13, ...)
            var type = new SpnTypeDescriptor("Compat",
                    new Constraint.ModuloEquals(6, 1),
                    new Constraint.ModuloEquals(2, 1));

            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(TypeConsistencyChecker.hasErrors(diagnostics));
        }

        @Test
        void moduloWithNoSolutionInInterval() {
            // n >= 0, n <= 1, n % 3 == 2 → need n=2 but 2 > 1
            var type = new SpnTypeDescriptor("Bad",
                    new Constraint.GreaterThanOrEqual(0),
                    new Constraint.LessThanOrEqual(1),
                    new Constraint.ModuloEquals(3, 2));

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasError(diagnostics, EMPTY_TYPE));
        }

        @Test
        void moduloWithSolutionInInterval() {
            // n >= 0, n <= 10, n % 3 == 2 → n = 2, 5, 8
            var type = new SpnTypeDescriptor("Ok",
                    new Constraint.GreaterThanOrEqual(0),
                    new Constraint.LessThanOrEqual(10),
                    new Constraint.ModuloEquals(3, 2));

            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(TypeConsistencyChecker.hasErrors(diagnostics));
        }

        @Test
        void noConstraints() {
            var type = new SpnTypeDescriptor("Any");
            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(TypeConsistencyChecker.hasErrors(diagnostics));
            assertTrue(hasInfo(diagnostics, FEASIBILITY));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. RULE CONFLICTS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class RuleConflicts {

        @Test
        void noConflictWhenResultsSame() {
            var omega = new SpnDistinguishedElement("Omega");
            // Two rules that produce the same result for overlapping inputs
            var type = SpnTypeDescriptor.builder("T")
                    .element(omega)
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.ExactLong(5), new OperandPattern.ExactLong(0), omega))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(hasWarning(diagnostics, RULE_CONFLICT));
        }

        @Test
        void conflictWhenResultsDiffer() {
            var omega = new SpnDistinguishedElement("Omega");
            // n / 0 = Omega  AND  5 / 0 = 42  → overlap at (5, 0) with different results
            var type = SpnTypeDescriptor.builder("T")
                    .element(omega)
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.ExactLong(5), new OperandPattern.ExactLong(0), 42L))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasWarning(diagnostics, RULE_CONFLICT));
        }

        @Test
        void noConflictForDifferentOperations() {
            var omega = new SpnDistinguishedElement("Omega");
            // n / 0 = Omega  AND  n * 0 = 0  → different operations, no conflict
            var type = SpnTypeDescriptor.builder("T")
                    .constraint(new Constraint.GreaterThanOrEqual(0))
                    .element(omega)
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                    .rule(new AlgebraicRule(Operation.MUL,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), 0L))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(hasWarning(diagnostics, RULE_CONFLICT));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. RULE OUTPUT VALIDATION
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class RuleOutputValidation {

        @Test
        void validRuleOutputAsElement() {
            var omega = new SpnDistinguishedElement("Omega");
            var type = SpnTypeDescriptor.builder("T")
                    .constraint(new Constraint.GreaterThanOrEqual(0))
                    .element(omega)
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(hasError(diagnostics, RULE_OUTPUT_VIOLATION));
        }

        @Test
        void validRuleOutputAsConstraintSatisfyingValue() {
            // n >= 0, rule: n * 0 = 0  → 0 satisfies n >= 0
            var type = SpnTypeDescriptor.builder("T")
                    .constraint(new Constraint.GreaterThanOrEqual(0))
                    .rule(new AlgebraicRule(Operation.MUL,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), 0L))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(hasError(diagnostics, RULE_OUTPUT_VIOLATION));
        }

        @Test
        void invalidRuleOutputViolatesConstraint() {
            // n >= 0, rule: n * 0 = -1  → -1 violates n >= 0
            var type = SpnTypeDescriptor.builder("T")
                    .constraint(new Constraint.GreaterThanOrEqual(0))
                    .rule(new AlgebraicRule(Operation.MUL,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), -1L))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasError(diagnostics, RULE_OUTPUT_VIOLATION));
        }

        @Test
        void ruleOutputIsUnknownElement() {
            var omega = new SpnDistinguishedElement("Omega");
            var foreign = new SpnDistinguishedElement("Foreign");
            // Rule produces 'Foreign' but only 'Omega' is an element of this type
            var type = SpnTypeDescriptor.builder("T")
                    .element(omega)
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), foreign))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasError(diagnostics, RULE_OUTPUT_VIOLATION));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. UNREACHABLE RULES
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class UnreachableRules {

        @Test
        void specificRuleShadowedByGeneral() {
            var omega = new SpnDistinguishedElement("Omega");
            // Rule 1: Any / 0 = Omega (general)
            // Rule 2: 5 / 0 = Omega   (specific, completely shadowed by rule 1)
            var type = SpnTypeDescriptor.builder("T")
                    .element(omega)
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.ExactLong(5), new OperandPattern.ExactLong(0), omega))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasWarning(diagnostics, UNREACHABLE_RULE));
        }

        @Test
        void specificBeforeGeneralIsNotUnreachable() {
            var omega = new SpnDistinguishedElement("Omega");
            // Rule 1: 5 / 0 = 42     (specific, fires first for input (5,0))
            // Rule 2: Any / 0 = Omega (general, catches everything else)
            var type = SpnTypeDescriptor.builder("T")
                    .constraint(new Constraint.GreaterThanOrEqual(0))
                    .element(omega)
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.ExactLong(5), new OperandPattern.ExactLong(0), 42L))
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertFalse(hasWarning(diagnostics, UNREACHABLE_RULE));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. ELEMENT COVERAGE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ElementCoverage {

        @Test
        void unreferencedElementWarning() {
            var omega = new SpnDistinguishedElement("Omega");
            // Element exists but no rules reference it at all
            var type = SpnTypeDescriptor.builder("T")
                    .element(omega)
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasWarning(diagnostics, ELEMENT_COVERAGE));
            assertTrue(diagnostics.stream()
                    .anyMatch(d -> d.message().contains("not referenced by any rule")));
        }

        @Test
        void partialCoverageWarning() {
            var omega = new SpnDistinguishedElement("Omega");
            // Omega only has a DIV rule; ADD, SUB, MUL, MOD are uncovered
            var type = SpnTypeDescriptor.builder("T")
                    .element(omega)
                    .rule(new AlgebraicRule(Operation.DIV,
                            new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                    .build();

            var diagnostics = TypeConsistencyChecker.check(type);

            assertTrue(hasWarning(diagnostics, ELEMENT_COVERAGE));
            assertTrue(diagnostics.stream()
                    .anyMatch(d -> d.message().contains("has no rules for operations")));
        }

        @Test
        void fullCoverageNoWarning() {
            var omega = new SpnDistinguishedElement("Omega");
            var builder = SpnTypeDescriptor.builder("T").element(omega);

            // Add rules for Omega as left operand in ALL operations
            for (Operation op : Operation.values()) {
                builder.rule(new AlgebraicRule(op,
                        new OperandPattern.IsElement(omega),
                        new OperandPattern.Any(),
                        omega));
            }

            var diagnostics = TypeConsistencyChecker.check(builder.build());

            // Should not have "not referenced" or "no rules for operations" warnings
            assertFalse(diagnostics.stream()
                    .anyMatch(d -> d.category() == ELEMENT_COVERAGE
                            && d.message().contains("not referenced")));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FULL INTEGRATION: ExtendedNatural from the design doc
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void extendedNaturalIsConsistent() {
        var omega = new SpnDistinguishedElement("Omega");

        var extNat = SpnTypeDescriptor.builder("ExtendedNatural")
                .constraint(new Constraint.GreaterThanOrEqual(0))
                .constraint(new Constraint.ModuloEquals(1, 0))
                .element(omega)
                // Division by zero → Omega
                .rule(new AlgebraicRule(Operation.DIV,
                        new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                // Omega absorbs all operations
                .rule(new AlgebraicRule(Operation.ADD,
                        new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega))
                .rule(new AlgebraicRule(Operation.ADD,
                        new OperandPattern.Any(), new OperandPattern.IsElement(omega), omega))
                .rule(new AlgebraicRule(Operation.SUB,
                        new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega))
                .rule(new AlgebraicRule(Operation.SUB,
                        new OperandPattern.Any(), new OperandPattern.IsElement(omega), omega))
                .rule(new AlgebraicRule(Operation.MUL,
                        new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega))
                .rule(new AlgebraicRule(Operation.MUL,
                        new OperandPattern.Any(), new OperandPattern.IsElement(omega), omega))
                .rule(new AlgebraicRule(Operation.DIV,
                        new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega))
                .rule(new AlgebraicRule(Operation.MOD,
                        new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega))
                .rule(new AlgebraicRule(Operation.MOD,
                        new OperandPattern.Any(), new OperandPattern.IsElement(omega), omega))
                .build();

        var diagnostics = TypeConsistencyChecker.check(extNat);

        assertFalse(TypeConsistencyChecker.hasErrors(diagnostics),
                () -> "Unexpected errors: " + diagnostics);

        // Should have the feasibility info
        assertTrue(hasInfo(diagnostics, FEASIBILITY));
    }
}
