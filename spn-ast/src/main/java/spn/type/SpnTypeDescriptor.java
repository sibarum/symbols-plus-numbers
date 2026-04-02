package spn.type;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes a user-defined constrained type at runtime.
 *
 * A type descriptor is created once (when the type definition is evaluated) and then
 * shared by all values of that type. It is immutable and compilation-final, meaning
 * Graal treats it and all its fields as constants during JIT compilation.
 *
 * KEY TRUFFLE CONCEPT: @CompilationFinal(dimensions = 1)
 *
 * By default, @CompilationFinal marks a field as constant after first write. For arrays,
 * "dimensions = 1" additionally marks each *element* of the array as constant. This is
 * critical for @ExplodeLoop: Graal needs to see each Constraint, Rule, and Element
 * object as a constant to devirtualize their method calls.
 *
 * Example usage with builder:
 * <pre>
 *   var omega = new SpnDistinguishedElement("Omega");
 *
 *   SpnTypeDescriptor extNat = SpnTypeDescriptor.builder("ExtendedNatural")
 *       .constraint(new Constraint.GreaterThanOrEqual(0))
 *       .constraint(new Constraint.ModuloEquals(1, 0))
 *       .element(omega)
 *       .rule(new AlgebraicRule(Operation.DIV, new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
 *       .rule(new AlgebraicRule(Operation.ADD, new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega))
 *       .rule(new AlgebraicRule(Operation.ADD, new OperandPattern.Any(), new OperandPattern.IsElement(omega), omega))
 *       .build();
 * </pre>
 */
public final class SpnTypeDescriptor {

    private final String name;

    @CompilationFinal(dimensions = 1)
    private final Constraint[] constraints;

    @CompilationFinal(dimensions = 1)
    private final SpnDistinguishedElement[] elements;

    @CompilationFinal(dimensions = 1)
    private final AlgebraicRule[] rules;

    private SpnTypeDescriptor(String name, Constraint[] constraints,
                              SpnDistinguishedElement[] elements, AlgebraicRule[] rules) {
        this.name = name;
        this.constraints = constraints;
        this.elements = elements;
        this.rules = rules;
    }

    /** Simple constructor for types with only constraints (no elements or rules). */
    public SpnTypeDescriptor(String name, Constraint... constraints) {
        this(name, constraints, new SpnDistinguishedElement[0], new AlgebraicRule[0]);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public String getName() {
        return name;
    }

    public Constraint[] getConstraints() {
        return constraints;
    }

    public SpnDistinguishedElement[] getElements() {
        return elements;
    }

    public AlgebraicRule[] getRules() {
        return rules;
    }

    /**
     * Returns true if the given value is a distinguished element of this type.
     * Uses reference equality -- elements are singletons per type.
     */
    public boolean hasElement(SpnDistinguishedElement element) {
        for (SpnDistinguishedElement e : elements) {
            if (e == element) {
                return true;
            }
        }
        return false;
    }

    /**
     * Looks up a distinguished element by name. Returns null if not found.
     */
    public SpnDistinguishedElement getElement(String elementName) {
        for (SpnDistinguishedElement e : elements) {
            if (e.getName().equals(elementName)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Returns true if this type has any algebraic rules defined.
     */
    public boolean hasRules() {
        return rules.length > 0;
    }

    /**
     * Returns true if this type has any distinguished elements defined.
     */
    public boolean hasElements() {
        return elements.length > 0;
    }

    /**
     * Checks whether a value satisfies all constraints of this type.
     * Distinguished elements bypass constraint checking.
     * This is the non-Truffle-compiled path -- used during validation and testing.
     *
     * @return null if all constraints pass (or value is a distinguished element),
     *         or the first violated Constraint
     */
    /**
     * Checks whether a value satisfies all constraints of this type.
     * Distinguished elements bypass constraint checking.
     * This is the non-Truffle-compiled path -- used during validation and testing.
     *
     * @return null if all constraints pass (or value is a known distinguished element),
     *         or the first violated Constraint
     * @throws IllegalArgumentException if value is an unknown distinguished element
     */
    public Constraint findViolation(Object value) {
        if (value instanceof SpnDistinguishedElement element) {
            if (hasElement(element)) {
                return null;
            }
            throw new IllegalArgumentException(
                    "Element '" + element.getName() + "' is not a member of type " + name);
        }
        for (Constraint constraint : constraints) {
            if (!constraint.check(value)) {
                return constraint;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder(name);
        if (constraints.length > 0) {
            sb.append(" where ");
            for (int i = 0; i < constraints.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(constraints[i].describe());
            }
        }
        if (elements.length > 0) {
            sb.append(" with ");
            for (int i = 0; i < elements.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(elements[i].getName());
            }
        }
        return sb.toString();
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    public static final class Builder {
        private final String name;
        private final List<Constraint> constraints = new ArrayList<>();
        private final List<SpnDistinguishedElement> elements = new ArrayList<>();
        private final List<AlgebraicRule> rules = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder constraint(Constraint constraint) {
            constraints.add(constraint);
            return this;
        }

        public Builder element(SpnDistinguishedElement element) {
            elements.add(element);
            return this;
        }

        public Builder rule(AlgebraicRule rule) {
            rules.add(rule);
            return this;
        }

        public SpnTypeDescriptor build() {
            return new SpnTypeDescriptor(
                    name,
                    constraints.toArray(new Constraint[0]),
                    elements.toArray(new SpnDistinguishedElement[0]),
                    rules.toArray(new AlgebraicRule[0])
            );
        }
    }

}
