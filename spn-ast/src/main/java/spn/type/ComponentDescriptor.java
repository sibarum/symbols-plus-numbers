package spn.type;

/**
 * Describes one named component of a product type.
 *
 * A product type (like Complex, Vec2, Bivector) has a fixed set of named components.
 * Each component has a name (for human readability and DSL access) and an index
 * (for fast runtime access into the component array).
 *
 * The index is assigned sequentially by the SpnTypeDescriptor builder:
 * <pre>
 *   builder.component("real")   → ComponentDescriptor("real", 0)
 *   builder.component("imag")   → ComponentDescriptor("imag", 1)
 * </pre>
 */
public record ComponentDescriptor(String name, int index) {

    @Override
    public String toString() {
        return name;
    }
}
