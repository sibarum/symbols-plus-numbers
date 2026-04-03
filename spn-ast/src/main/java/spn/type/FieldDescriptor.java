package spn.type;

/**
 * Describes one field of a struct: a name and a type.
 *
 * The name is for human readability and DSL access. The type determines
 * what values are acceptable at runtime. Validation happens at AST execution
 * time in SpnStructConstructNode.
 *
 * <pre>
 *   FieldDescriptor.untyped("x")                             // any value
 *   FieldDescriptor.typed("radius", FieldType.DOUBLE)        // must be Double
 *   FieldDescriptor.typed("center", FieldType.ofProduct(vec2Type))  // must be Vec2
 *   FieldDescriptor.generic("value", "T")                    // generic param T
 * </pre>
 */
public record FieldDescriptor(String name, FieldType type) {

    /** Creates an untyped field (accepts any value). */
    public static FieldDescriptor untyped(String name) {
        return new FieldDescriptor(name, FieldType.UNTYPED);
    }

    /** Creates a typed field. */
    public static FieldDescriptor typed(String name, FieldType type) {
        return new FieldDescriptor(name, type);
    }

    /** Creates a field with a generic type parameter. */
    public static FieldDescriptor generic(String name, String typeParam) {
        return new FieldDescriptor(name, FieldType.generic(typeParam));
    }

    /** Returns true if this field has a concrete (non-untyped, non-generic) type. */
    public boolean isTyped() {
        return !(type instanceof FieldType.Untyped) && !(type instanceof FieldType.GenericParam);
    }

    @Override
    public String toString() {
        if (type instanceof FieldType.Untyped) return name;
        return name + ": " + type.describe();
    }
}
