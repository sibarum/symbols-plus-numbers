package spn.type;

import java.util.Arrays;

/**
 * A runtime anonymous tuple value -- positional, immutable, structurally typed.
 *
 * Tuples are the anonymous counterpart to structs. Where structs have names and
 * use nominal typing, tuples are unnamed and matched structurally (by arity and
 * element types).
 *
 * <pre>
 *   var desc = new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING);
 *   var tuple = new SpnTupleValue(desc, 42L, "hello");
 *   tuple.get(0)  // 42L
 *   tuple.get(1)  // "hello"
 *   tuple.arity() // 2
 * </pre>
 */
public final class SpnTupleValue {

    private final SpnTupleDescriptor descriptor;
    private final Object[] elements;

    public SpnTupleValue(SpnTupleDescriptor descriptor, Object... elements) {
        this.descriptor = descriptor;
        this.elements = elements;
    }

    public SpnTupleDescriptor getDescriptor() {
        return descriptor;
    }

    public Object get(int index) {
        return elements[index];
    }

    public Object[] getElements() {
        return elements;
    }

    public int arity() {
        return elements.length;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("(");
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(elements[i]);
        }
        return sb.append(")").toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SpnTupleValue other)) return false;
        return Arrays.equals(elements, other.elements);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(elements);
    }
}
