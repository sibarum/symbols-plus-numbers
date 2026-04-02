package spn.type.check;

import spn.type.AlgebraicRule;
import spn.type.Constraint;
import spn.type.Constraint.GreaterThan;
import spn.type.Constraint.GreaterThanOrEqual;
import spn.type.Constraint.LessThan;
import spn.type.Constraint.LessThanOrEqual;
import spn.type.Constraint.ModuloEquals;
import spn.type.Operation;
import spn.type.OperandPattern;
import spn.type.SpnDistinguishedElement;
import spn.type.SpnTypeDescriptor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static spn.type.check.Diagnostic.Category.*;

/**
 * Validates a SpnTypeDescriptor for internal consistency.
 *
 * This checker performs static analysis on a type definition to catch contradictions,
 * conflicts, and likely mistakes BEFORE the type is ever used at runtime. It runs at
 * type-definition time (not in the hot loop), so performance is not critical.
 *
 * Checks performed:
 *
 * 1. CONSTRAINT SATISFIABILITY
 *    Computes the feasible interval from comparison constraints (>=, >, <=, <) and
 *    checks if the interval is empty. Then checks modular constraints (n % d == r)
 *    for mutual compatibility using the Chinese Remainder Theorem, and for the
 *    existence of solutions within the feasible interval.
 *
 * 2. RULE CONFLICTS
 *    For each pair of rules with the same operation, checks if their operand patterns
 *    can match the same input. If so and the results differ, flags a warning (the
 *    first rule wins at runtime, but it may be unintentional).
 *
 * 3. RULE OUTPUT VALIDATION
 *    For each rule, checks if the result value satisfies the type's constraints
 *    (or is a distinguished element of the type). A rule that produces a constraint-
 *    violating non-element result is always an error.
 *
 * 4. UNREACHABLE RULES
 *    Detects rules that are completely shadowed by earlier rules with more general
 *    patterns. These rules can never fire and likely indicate a mistake.
 *
 * 5. ELEMENT COVERAGE
 *    For each distinguished element, checks if it appears in any rule's patterns.
 *    An element with no rule coverage means any operation involving it will throw
 *    a runtime error.
 *
 * Usage:
 * <pre>
 *   SpnTypeDescriptor type = SpnTypeDescriptor.builder("Natural")
 *       .constraint(new Constraint.GreaterThanOrEqual(0))
 *       .constraint(new Constraint.ModuloEquals(1, 0))
 *       .build();
 *
 *   List&lt;Diagnostic&gt; diagnostics = TypeConsistencyChecker.check(type);
 *   for (Diagnostic d : diagnostics) {
 *       System.out.println(d);
 *   }
 *   // [INFO] FEASIBILITY: Type 'Natural' admits values in [0, +Inf) where n % 1 == 0
 * </pre>
 */
public final class TypeConsistencyChecker {

    private TypeConsistencyChecker() {
    }

    /**
     * Performs all consistency checks on the given type descriptor.
     *
     * @return a list of diagnostics, possibly empty if the type is fully consistent
     */
    public static List<Diagnostic> check(SpnTypeDescriptor type) {
        var diagnostics = new ArrayList<Diagnostic>();
        checkConstraintSatisfiability(type, diagnostics);
        checkRuleConflicts(type, diagnostics);
        checkRuleOutputs(type, diagnostics);
        checkUnreachableRules(type, diagnostics);
        checkElementCoverage(type, diagnostics);
        return diagnostics;
    }

    /** Returns true if any diagnostic in the list is an ERROR. */
    public static boolean hasErrors(List<Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 1. CONSTRAINT SATISFIABILITY
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Computes the feasible value interval from comparison constraints, checks
     * modular constraints for mutual compatibility (CRT), and verifies that
     * modular solutions exist within the interval.
     */
    private static void checkConstraintSatisfiability(SpnTypeDescriptor type,
                                                      List<Diagnostic> diagnostics) {
        Constraint[] constraints = type.getConstraints();
        if (constraints.length == 0) {
            diagnostics.add(Diagnostic.info(FEASIBILITY,
                    "Type '" + type.getName() + "' has no constraints — admits all values."));
            return;
        }

        // ── Step 1: Compute feasible interval from comparison constraints ───
        Interval interval = computeInterval(constraints);

        if (interval.isEmpty()) {
            diagnostics.add(Diagnostic.error(EMPTY_TYPE,
                    "Type '" + type.getName() + "' has contradictory comparison constraints — "
                            + "no value satisfies " + interval.describeConflict() + "."));
            return;
        }

        // ── Step 2: Collect modular constraints and check pairwise CRT ──────
        List<ModuloEquals> moduloConstraints = new ArrayList<>();
        for (Constraint c : constraints) {
            if (c instanceof ModuloEquals m) {
                moduloConstraints.add(m);
            }
        }

        for (int i = 0; i < moduloConstraints.size(); i++) {
            for (int j = i + 1; j < moduloConstraints.size(); j++) {
                ModuloEquals m1 = moduloConstraints.get(i);
                ModuloEquals m2 = moduloConstraints.get(j);
                if (!moduloConstraintsCompatible(m1, m2)) {
                    diagnostics.add(Diagnostic.error(EMPTY_TYPE,
                            "Type '" + type.getName() + "' has incompatible modular constraints: "
                                    + m1.describe() + " and " + m2.describe()
                                    + " have no common solutions."));
                    return;
                }
            }
        }

        // ── Step 3: Check that modular solutions exist in the interval ──────
        if (!moduloConstraints.isEmpty() && interval.isFinite()) {
            boolean hasSolution = moduloHasSolutionInInterval(moduloConstraints, interval);
            if (!hasSolution) {
                diagnostics.add(Diagnostic.error(EMPTY_TYPE,
                        "Type '" + type.getName() + "' has no values satisfying both "
                                + interval.describe() + " and the modular constraints."));
                return;
            }
        }

        // ── Step 4: Produce informational feasibility description ───────────
        var desc = new StringBuilder("Type '").append(type.getName()).append("' admits values in ")
                .append(interval.describe());
        if (!moduloConstraints.isEmpty()) {
            desc.append(" where ");
            for (int i = 0; i < moduloConstraints.size(); i++) {
                if (i > 0) desc.append(" and ");
                desc.append(moduloConstraints.get(i).describe());
            }
        }
        diagnostics.add(Diagnostic.info(FEASIBILITY, desc.toString()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. RULE CONFLICTS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * For each pair of rules on the same operation, checks if their patterns overlap.
     * If they do and the results differ, that's a potential conflict (resolved by
     * rule ordering at runtime, but likely unintentional).
     */
    private static void checkRuleConflicts(SpnTypeDescriptor type, List<Diagnostic> diagnostics) {
        AlgebraicRule[] rules = type.getRules();
        for (int i = 0; i < rules.length; i++) {
            for (int j = i + 1; j < rules.length; j++) {
                AlgebraicRule a = rules[i];
                AlgebraicRule b = rules[j];
                if (a.operation() != b.operation()) continue;
                if (!patternsOverlap(a.left(), b.left())) continue;
                if (!patternsOverlap(a.right(), b.right())) continue;

                if (!resultsEqual(a.result(), b.result())) {
                    diagnostics.add(Diagnostic.warning(RULE_CONFLICT,
                            "Rules overlap with different results in type '" + type.getName()
                                    + "': [" + a.describe() + "] and [" + b.describe()
                                    + "]. First rule wins at runtime."));
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3. RULE OUTPUT VALIDATION
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Checks that every rule's result either is a distinguished element of the type
     * or satisfies all of the type's constraints.
     */
    private static void checkRuleOutputs(SpnTypeDescriptor type, List<Diagnostic> diagnostics) {
        for (AlgebraicRule rule : type.getRules()) {
            Object result = rule.result();

            // Distinguished elements bypass constraints
            if (result instanceof SpnDistinguishedElement element) {
                if (!type.hasElement(element)) {
                    diagnostics.add(Diagnostic.error(RULE_OUTPUT_VIOLATION,
                            "Rule [" + rule.describe() + "] in type '" + type.getName()
                                    + "' produces element '" + element.getName()
                                    + "' which is not a member of this type."));
                }
                continue;
            }

            // Check the result against all constraints
            Constraint violation = type.findViolation(result);
            if (violation != null) {
                diagnostics.add(Diagnostic.error(RULE_OUTPUT_VIOLATION,
                        "Rule [" + rule.describe() + "] in type '" + type.getName()
                                + "' produces " + result + " which violates constraint '"
                                + violation.describe() + "'."));
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 4. UNREACHABLE RULES
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Detects rules that are completely shadowed by an earlier rule with the same
     * operation and patterns that are at least as general.
     */
    private static void checkUnreachableRules(SpnTypeDescriptor type,
                                              List<Diagnostic> diagnostics) {
        AlgebraicRule[] rules = type.getRules();
        for (int i = 0; i < rules.length; i++) {
            for (int j = 0; j < i; j++) {
                AlgebraicRule earlier = rules[j];
                AlgebraicRule later = rules[i];
                if (earlier.operation() != later.operation()) continue;
                if (patternSubsumedBy(later.left(), earlier.left())
                        && patternSubsumedBy(later.right(), earlier.right())) {
                    diagnostics.add(Diagnostic.warning(UNREACHABLE_RULE,
                            "Rule [" + later.describe() + "] in type '" + type.getName()
                                    + "' is unreachable — completely shadowed by earlier rule ["
                                    + earlier.describe() + "]."));
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 5. ELEMENT COVERAGE
    // ════════════════════════════════════════════════════════════════════════

    /**
     * For each distinguished element, checks if it appears in any rule's patterns
     * or results. An element with no rule coverage is essentially useless — any
     * operation involving it will throw at runtime.
     */
    private static void checkElementCoverage(SpnTypeDescriptor type,
                                             List<Diagnostic> diagnostics) {
        AlgebraicRule[] rules = type.getRules();
        for (SpnDistinguishedElement element : type.getElements()) {
            boolean referenced = false;

            for (AlgebraicRule rule : rules) {
                if (referencesElement(rule, element)) {
                    referenced = true;
                    break;
                }
            }

            if (!referenced) {
                diagnostics.add(Diagnostic.warning(ELEMENT_COVERAGE,
                        "Distinguished element '" + element.getName() + "' in type '"
                                + type.getName()
                                + "' is not referenced by any rule. Operations involving "
                                + element.getName() + " will always fail at runtime."));
                continue;
            }

            // Check which operations have rules covering this element as an operand
            Set<Operation> coveredOps = EnumSet.noneOf(Operation.class);
            for (AlgebraicRule rule : rules) {
                if (patternMatchesElement(rule.left(), element)
                        || patternMatchesElement(rule.right(), element)) {
                    coveredOps.add(rule.operation());
                }
            }

            Set<Operation> allOps = EnumSet.allOf(Operation.class);
            allOps.removeAll(coveredOps);
            if (!allOps.isEmpty()) {
                diagnostics.add(Diagnostic.warning(ELEMENT_COVERAGE,
                        "Element '" + element.getName() + "' in type '" + type.getName()
                                + "' has no rules for operations: " + allOps
                                + ". These operations will fail at runtime if "
                                + element.getName() + " is an operand."));
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // INTERVAL ANALYSIS
    // ════════════════════════════════════════════════════════════════════════

    private record Interval(
            double lower, boolean lowerInclusive,
            double upper, boolean upperInclusive
    ) {
        static final Interval ALL = new Interval(
                Double.NEGATIVE_INFINITY, false, Double.POSITIVE_INFINITY, false);

        boolean isEmpty() {
            if (lower > upper) return true;
            if (lower == upper) return !lowerInclusive || !upperInclusive;
            return false;
        }

        boolean isFinite() {
            return Double.isFinite(lower) && Double.isFinite(upper);
        }

        String describe() {
            String lb = lowerInclusive ? "[" : "(";
            String rb = upperInclusive ? "]" : ")";
            String lo = Double.isInfinite(lower) ? "-Inf" : formatNum(lower);
            String hi = Double.isInfinite(upper) ? "+Inf" : formatNum(upper);
            return lb + lo + ", " + hi + rb;
        }

        String describeConflict() {
            return "lower bound " + (lowerInclusive ? ">= " : "> ") + formatNum(lower)
                    + " and upper bound " + (upperInclusive ? "<= " : "< ") + formatNum(upper);
        }
    }

    private static Interval computeInterval(Constraint[] constraints) {
        double lower = Double.NEGATIVE_INFINITY;
        boolean lowerInclusive = false;
        double upper = Double.POSITIVE_INFINITY;
        boolean upperInclusive = false;

        for (Constraint c : constraints) {
            switch (c) {
                case GreaterThanOrEqual gte -> {
                    if (gte.bound() > lower
                            || (gte.bound() == lower && !lowerInclusive)) {
                        lower = gte.bound();
                        lowerInclusive = true;
                    }
                }
                case GreaterThan gt -> {
                    if (gt.bound() > lower
                            || (gt.bound() == lower && lowerInclusive)) {
                        lower = gt.bound();
                        lowerInclusive = false;
                    }
                }
                case LessThanOrEqual lte -> {
                    if (lte.bound() < upper
                            || (lte.bound() == upper && !upperInclusive)) {
                        upper = lte.bound();
                        upperInclusive = true;
                    }
                }
                case LessThan lt -> {
                    if (lt.bound() < upper
                            || (lt.bound() == upper && upperInclusive)) {
                        upper = lt.bound();
                        upperInclusive = false;
                    }
                }
                default -> {
                    // ModuloEquals handled separately
                }
            }
        }

        return new Interval(lower, lowerInclusive, upper, upperInclusive);
    }

    // ════════════════════════════════════════════════════════════════════════
    // MODULAR ARITHMETIC
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Two modular constraints n % d1 == r1 and n % d2 == r2 are compatible iff
     * (r1 - r2) is divisible by gcd(d1, d2). This is a necessary condition from
     * the Chinese Remainder Theorem.
     */
    private static boolean moduloConstraintsCompatible(ModuloEquals m1, ModuloEquals m2) {
        long g = gcd(m1.divisor(), m2.divisor());
        long diff = m1.remainder() - m2.remainder();
        return diff % g == 0;
    }

    /**
     * Checks if there exists an integer in the interval that satisfies ALL modular
     * constraints. Uses brute-force search over the smallest modular period within
     * the interval bounds. This is tractable because intervals are typically small
     * or the combined period is small.
     */
    private static boolean moduloHasSolutionInInterval(List<ModuloEquals> mods, Interval interval) {
        // Compute the LCM of all divisors to find the combined period
        long lcm = 1;
        for (ModuloEquals m : mods) {
            lcm = lcm(lcm, m.divisor());
            if (lcm > 1_000_000) {
                // Period too large for brute force — assume satisfiable
                // (pairwise CRT already passed, so this is very likely true)
                return true;
            }
        }

        // Search one full period starting from the interval's lower bound
        long start = (long) Math.ceil(interval.lower());
        if (!interval.lowerInclusive() && interval.lower() == start) {
            start++;
        }
        long end = Math.min(start + lcm, (long) Math.floor(interval.upper()));
        if (!interval.upperInclusive() && interval.upper() == end) {
            end--;
        }

        for (long candidate = start; candidate <= end; candidate++) {
            boolean satisfiesAll = true;
            for (ModuloEquals m : mods) {
                if (candidate % m.divisor() != m.remainder()) {
                    satisfiesAll = false;
                    break;
                }
            }
            if (satisfiesAll) {
                return true;
            }
        }
        return false;
    }

    private static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    private static long lcm(long a, long b) {
        return (a / gcd(a, b)) * b;
    }

    // ════════════════════════════════════════════════════════════════════════
    // PATTERN ANALYSIS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if two operand patterns can match the same value.
     */
    private static boolean patternsOverlap(OperandPattern a, OperandPattern b) {
        // Any overlaps with everything
        if (a instanceof OperandPattern.Any || b instanceof OperandPattern.Any) return true;

        // AnyNumber overlaps with numeric patterns
        if (a instanceof OperandPattern.AnyNumber || b instanceof OperandPattern.AnyNumber) {
            // AnyNumber does NOT overlap with IsElement
            if (a instanceof OperandPattern.IsElement || b instanceof OperandPattern.IsElement) {
                return false;
            }
            return true;
        }

        // Same exact type: compare values
        if (a instanceof OperandPattern.ExactLong el1 && b instanceof OperandPattern.ExactLong el2) {
            return el1.expected() == el2.expected();
        }
        if (a instanceof OperandPattern.ExactDouble ed1 && b instanceof OperandPattern.ExactDouble ed2) {
            return ed1.expected() == ed2.expected();
        }
        if (a instanceof OperandPattern.IsElement ie1 && b instanceof OperandPattern.IsElement ie2) {
            return ie1.element() == ie2.element();
        }

        // Cross-type: ExactLong and ExactDouble overlap if numerically equal
        if (a instanceof OperandPattern.ExactLong el && b instanceof OperandPattern.ExactDouble ed) {
            return el.expected() == ed.expected();
        }
        if (a instanceof OperandPattern.ExactDouble ed && b instanceof OperandPattern.ExactLong el) {
            return el.expected() == ed.expected();
        }

        // IsElement doesn't overlap with numeric exact patterns
        return false;
    }

    /**
     * Returns true if every value matched by 'specific' is also matched by 'general'.
     * Used for unreachable rule detection.
     */
    private static boolean patternSubsumedBy(OperandPattern specific, OperandPattern general) {
        if (general instanceof OperandPattern.Any) return true;
        if (specific.equals(general)) return true;

        if (general instanceof OperandPattern.AnyNumber) {
            return specific instanceof OperandPattern.ExactLong
                    || specific instanceof OperandPattern.ExactDouble
                    || specific instanceof OperandPattern.AnyNumber;
        }

        // ExactLong(x) is subsumed by ExactDouble(x.0) if values match
        if (general instanceof OperandPattern.ExactDouble ed
                && specific instanceof OperandPattern.ExactLong el) {
            return el.expected() == ed.expected();
        }

        return false;
    }

    /**
     * Returns true if the rule references the given element (in patterns or result).
     */
    private static boolean referencesElement(AlgebraicRule rule, SpnDistinguishedElement element) {
        if (rule.result() == element) return true;
        return patternMatchesElement(rule.left(), element)
                || patternMatchesElement(rule.right(), element);
    }

    /**
     * Returns true if the pattern specifically matches the given element.
     */
    private static boolean patternMatchesElement(OperandPattern pattern,
                                                 SpnDistinguishedElement element) {
        return pattern instanceof OperandPattern.IsElement ie && ie.element() == element;
    }

    /**
     * Compares two rule results for equality.
     */
    private static boolean resultsEqual(Object a, Object b) {
        if (a == b) return true;  // covers same element reference
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private static String formatNum(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
