package spn.type;

/**
 * Describes one named component of a numeric/algebraic product type.
 *
 * Each component has:
 *   - A name (for human readability and DSL access)
 *   - An index (for fast runtime access into the component array)
 *   - A FieldType (what kind of value: Double, Long, Symbol, etc.)
 *   - Optional per-component Constraints (e.g., SymbolOneOf for symbol components)
 *
 * Per-component constraints are checked during product construction and after
 * operations, independently of the type-level constraints on SpnTypeDescriptor.
 *
 * Examples:
 * <pre>
 *   // Untyped component (backwards compatible)
 *   new ComponentDescriptor("real", 0)
 *
 *   // Typed component
 *   new ComponentDescriptor("real", 0, FieldType.DOUBLE)
 *
 *   // Symbol component with constraint
 *   new ComponentDescriptor("color", 1, FieldType.SYMBOL,
 *       Constraint.SymbolOneOf.of(red, green, blue))
 * </pre>
 */
public final class ComponentDescriptor {

    private final String name;
    private final int index;
    private final FieldType type;
    private final Constraint[] constraints;

    public ComponentDescriptor(String name, int index, FieldType type, Constraint... constraints) {
        this.name = name;
        this.index = index;
        this.type = type;
        this.constraints = constraints;
    }

    /** Backwards-compatible: untyped, no constraints. */
    public ComponentDescriptor(String name, int index) {
        this(name, index, FieldType.UNTYPED);
    }

    /** Typed, no per-component constraints. */
    public ComponentDescriptor(String name, int index, FieldType type) {
        this(name, index, type, new Constraint[0]);
    }

    public String name() { return name; }
    public int index() { return index; }
    public FieldType type() { return type; }
    public Constraint[] constraints() { return constraints; }

    /** Returns true if this component has a concrete type or per-component constraints. */
    public boolean hasValidation() {
        return !(type instanceof FieldType.Untyped) || constraints.length > 0;
    }

    /**
     * Validates a value against this component's type and constraints.
     * Returns null if valid, or a description of the first violation.
     */
    public String validate(Object value) {
        if (!type.accepts(value)) {
            return "component '" + name + "' expects " + type.describe()
                    + ", got " + value.getClass().getSimpleName();
        }
        for (Constraint c : constraints) {
            if (!c.check(value)) {
                return "component '" + name + "' violates constraint '" + c.describe() + "'";
            }
        }
        return null;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder(name);
        if (!(type instanceof FieldType.Untyped)) {
            sb.append(": ").append(type.describe());
        }
        if (constraints.length > 0) {
            sb.append(" where ");
            for (int i = 0; i < constraints.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(constraints[i].describe());
            }
        }
        return sb.toString();
    }
}
