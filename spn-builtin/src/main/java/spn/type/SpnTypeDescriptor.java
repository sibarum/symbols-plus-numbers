package spn.type;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes a user-defined constrained type at runtime.
 *
 * A type descriptor is created once (when the type definition is evaluated) and then
 * shared by all values of that type. It is immutable.
 *
 * A type can be either scalar (single value + constraints) or a product type
 * (multiple named components + operation definitions). Product types enable
 * user-defined multi-component algebraic objects like complex numbers, vectors,
 * bivectors, quaternions, and matrices.
 *
 * Example scalar type:
 * <pre>
 *   SpnTypeDescriptor natural = SpnTypeDescriptor.builder("Natural")
 *       .constraint(new Constraint.GreaterThanOrEqual(0))
 *       .constraint(new Constraint.ModuloEquals(1, 0))
 *       .build();
 * </pre>
 *
 * Example product type:
 * <pre>
 *   import static spn.type.ComponentExpression.*;
 *
 *   SpnTypeDescriptor complex = SpnTypeDescriptor.builder("Complex")
 *       .component("real")
 *       .component("imag")
 *       .productRule(Operation.ADD,
 *           add(left(0), right(0)),   // result.real = left.real + right.real
 *           add(left(1), right(1)))   // result.imag = left.imag + right.imag
 *       .productRule(Operation.MUL,
 *           sub(mul(left(0), right(0)), mul(left(1), right(1))),   // ac - bd
 *           add(mul(left(0), right(1)), mul(left(1), right(0))))   // ad + bc
 *       .build();
 * </pre>
 */
public final class SpnTypeDescriptor {

    private final String name;

    /** The name of the constrained value parameter, e.g. "n" in type Natural(n). */
    private final String valueParam;

    private final Constraint[] constraints;
    private final SpnDistinguishedElement[] elements;
    private final AlgebraicRule[] rules;
    private final ComponentDescriptor[] componentDescriptors;
    private final ProductOperationDef[] productOperationDefs;

    private SpnTypeDescriptor(String name, String valueParam, Constraint[] constraints,
                              SpnDistinguishedElement[] elements, AlgebraicRule[] rules,
                              ComponentDescriptor[] componentDescriptors,
                              ProductOperationDef[] productOperationDefs) {
        this.name = name;
        this.valueParam = valueParam;
        this.constraints = constraints;
        this.elements = elements;
        this.rules = rules;
        this.componentDescriptors = componentDescriptors;
        this.productOperationDefs = productOperationDefs;
    }

    /** Simple constructor for scalar types with only constraints (no elements or rules). */
    public SpnTypeDescriptor(String name, Constraint... constraints) {
        this(name, null, constraints, new SpnDistinguishedElement[0], new AlgebraicRule[0],
                new ComponentDescriptor[0], new ProductOperationDef[0]);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public String getName() {
        return name;
    }

    /**
     * Returns the value parameter name for scalar constrained types, e.g. "n" in
     * type Natural(n) where n >= 0. Returns null if no value parameter was declared
     * (backwards compatibility with the simple constructor).
     */
    public String getValueParam() {
        return valueParam;
    }

    /**
     * Returns true if this type has an explicit value parameter name.
     */
    public boolean hasValueParam() {
        return valueParam != null;
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
     * Describes a constraint using this type's value parameter name (if set),
     * falling back to the constraint's default description.
     */
    public String describeConstraint(Constraint constraint) {
        if (valueParam != null) {
            return constraint.describe(valueParam);
        }
        return constraint.describe();
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

    // ── Product type accessors ──────────────────────────────────────────────

    public ComponentDescriptor[] getComponentDescriptors() {
        return componentDescriptors;
    }

    public ProductOperationDef[] getProductOperationDefs() {
        return productOperationDefs;
    }

    /**
     * Returns true if this is a product type (has named components).
     */
    public boolean isProduct() {
        return componentDescriptors.length > 0;
    }

    /**
     * Number of components in this product type. Returns 0 for scalar types.
     */
    public int componentCount() {
        return componentDescriptors.length;
    }

    /**
     * Looks up a component's index by name. Returns -1 if not found.
     */
    public int componentIndex(String componentName) {
        for (ComponentDescriptor cd : componentDescriptors) {
            if (cd.name().equals(componentName)) {
                return cd.index();
            }
        }
        return -1;
    }

    /**
     * Returns true if any component has type or constraint validation.
     */
    public boolean hasComponentValidation() {
        for (ComponentDescriptor cd : componentDescriptors) {
            if (cd.hasValidation()) return true;
        }
        return false;
    }

    /**
     * Finds the ProductOperationDef for the given operation. Returns null if not defined.
     */
    public ProductOperationDef findProductOperation(Operation operation) {
        for (ProductOperationDef def : productOperationDefs) {
            if (def.operation() == operation) {
                return def;
            }
        }
        return null;
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
        if (valueParam != null && componentDescriptors.length == 0) {
            sb.append("(").append(valueParam).append(")");
        }
        if (componentDescriptors.length > 0) {
            sb.append("(");
            for (int i = 0; i < componentDescriptors.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(componentDescriptors[i].toString());
            }
            sb.append(")");
        }
        if (constraints.length > 0) {
            sb.append(" where ");
            for (int i = 0; i < constraints.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(describeConstraint(constraints[i]));
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
        private String valueParam;
        private String baseType;
        private Object validatorExpr; // SpnExpressionNode (stored as Object to avoid circular dep)
        private final List<Constraint> constraints = new ArrayList<>();
        private final List<SpnDistinguishedElement> elements = new ArrayList<>();
        private final List<AlgebraicRule> rules = new ArrayList<>();
        private final List<ComponentDescriptor> components = new ArrayList<>();
        private final List<ProductOperationDef> productDefs = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder baseType(String baseType) {
            this.baseType = baseType;
            return this;
        }

        public Builder validatorExpr(Object expr) {
            this.validatorExpr = expr;
            return this;
        }

        /**
         * Sets the value parameter name for scalar constrained types.
         * This is the variable name used in constraint descriptions:
         * <pre>
         *   type Natural(n) where n >= 0, n % 1 == 0
         * </pre>
         */
        public Builder valueParam(String name) {
            this.valueParam = name;
            return this;
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

        /**
         * Adds an untyped named component. Components are indexed in add order.
         */
        public Builder component(String name) {
            components.add(new ComponentDescriptor(name, components.size()));
            return this;
        }

        /**
         * Adds a typed component (e.g., FieldType.DOUBLE, FieldType.SYMBOL).
         */
        public Builder component(String name, FieldType type) {
            components.add(new ComponentDescriptor(name, components.size(), type));
            return this;
        }

        /**
         * Adds a typed component with per-component constraints.
         * <pre>
         *   builder.component("color", FieldType.SYMBOL,
         *       Constraint.SymbolOneOf.of(red, green, blue))
         * </pre>
         */
        public Builder component(String name, FieldType type, Constraint... constraints) {
            components.add(new ComponentDescriptor(name, components.size(), type, constraints));
            return this;
        }

        /**
         * Defines a binary operation on this product type. Each ComponentExpression
         * computes one component of the result, in component order.
         *
         * <pre>
         *   import static spn.type.ComponentExpression.*;
         *
         *   builder.productRule(Operation.ADD,
         *       add(left(0), right(0)),   // result component 0
         *       add(left(1), right(1)))   // result component 1
         * </pre>
         */
        public Builder productRule(Operation operation, ComponentExpression... componentResults) {
            productDefs.add(new ProductOperationDef(operation, componentResults));
            return this;
        }

        public SpnTypeDescriptor build() {
            return new SpnTypeDescriptor(
                    name,
                    valueParam,
                    constraints.toArray(new Constraint[0]),
                    elements.toArray(new SpnDistinguishedElement[0]),
                    rules.toArray(new AlgebraicRule[0]),
                    components.toArray(new ComponentDescriptor[0]),
                    productDefs.toArray(new ProductOperationDef[0])
            );
        }
    }

}
