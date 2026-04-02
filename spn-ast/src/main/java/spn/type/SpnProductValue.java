package spn.type;

import java.util.Arrays;

/**
 * A runtime value of a product type -- a fixed-size tuple of named components.
 *
 * Product values represent multi-component algebraic objects: complex numbers,
 * vectors, bivectors, quaternions, matrices, etc. Each component is stored as
 * a boxed primitive (Long, Double) and accessed by index for performance.
 *
 * Component layout:
 *   SpnTypeDescriptor defines the component order via ComponentDescriptor[].
 *   For "Complex" with components [real(0), imag(1)]:
 *     new SpnProductValue(complexType, 3.0, 4.0)  →  3 + 4i
 *     product.get(0) → 3.0 (real)
 *     product.get(1) → 4.0 (imag)
 *
 * Truffle performance:
 * Product values flow through the AST as Objects. When SpnProductBinaryNode
 * evaluates an operation, it extracts the component arrays from both operands
 * and passes them to ComponentExpression.evaluate(). Graal can often scalar-replace
 * the product value (eliminating the Object[] allocation) when the components are
 * consumed immediately by the next operation.
 */
public final class SpnProductValue {

    private final SpnTypeDescriptor type;
    private final Object[] components;

    public SpnProductValue(SpnTypeDescriptor type, Object... components) {
        this.type = type;
        this.components = components;
    }

    public SpnTypeDescriptor getType() {
        return type;
    }

    /** Returns the component array directly (no copy -- for performance). */
    public Object[] getComponents() {
        return components;
    }

    /** Gets one component by index. */
    public Object get(int index) {
        return components[index];
    }

    /** Number of components. */
    public int size() {
        return components.length;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder(type.getName()).append("(");
        ComponentDescriptor[] descs = type.getComponentDescriptors();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) sb.append(", ");
            if (descs.length > i) {
                sb.append(descs[i].name()).append("=");
            }
            sb.append(components[i]);
        }
        return sb.append(")").toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SpnProductValue other)) return false;
        return type == other.type && Arrays.equals(components, other.components);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(components) * 31 + System.identityHashCode(type);
    }
}
