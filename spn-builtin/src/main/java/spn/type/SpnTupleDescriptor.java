package spn.type;

import java.util.Arrays;

/**
 * Describes an anonymous tuple type with variable specificity per position.
 *
 * Unlike structs (nominal typing, identity by reference), tuples use structural
 * typing: two tuple descriptors with the same element types are interchangeable.
 *
 * Each position has a FieldType controlling what values are accepted:
 * <pre>
 *   // (Long, _, Double) -- position 0: Long, position 1: any, position 2: Double
 *   new SpnTupleDescriptor(FieldType.LONG, FieldType.UNTYPED, FieldType.DOUBLE)
 *
 *   // Fully untyped 3-tuple (accepts anything in every position)
 *   SpnTupleDescriptor.untyped(3)
 *
 *   // Nested: a tuple whose second element must itself be a tuple
 *   var inner = new SpnTupleDescriptor(FieldType.LONG, FieldType.LONG);
 *   var outer = new SpnTupleDescriptor(FieldType.STRING, FieldType.ofTuple(inner));
 * </pre>
 *
 * Structural matching:
 * A tuple value matches a descriptor if its arity matches and each element
 * satisfies the descriptor's FieldType at that position. This means a fully
 * untyped descriptor of arity N matches ANY N-tuple regardless of element types.
 */
public final class SpnTupleDescriptor {

    private final FieldType[] elementTypes;

    public SpnTupleDescriptor(FieldType... elementTypes) {
        this.elementTypes = elementTypes;
    }

    /** Creates a fully untyped tuple descriptor of the given arity. */
    public static SpnTupleDescriptor untyped(int arity) {
        FieldType[] types = new FieldType[arity];
        Arrays.fill(types, FieldType.UNTYPED);
        return new SpnTupleDescriptor(types);
    }

    public FieldType[] getElementTypes() {
        return elementTypes;
    }

    public int arity() {
        return elementTypes.length;
    }

    public FieldType elementType(int index) {
        return elementTypes[index];
    }

    /**
     * Returns true if this descriptor has any typed (non-Untyped) positions.
     */
    public boolean hasTypedPositions() {
        for (FieldType ft : elementTypes) {
            if (!(ft instanceof FieldType.Untyped)) return true;
        }
        return false;
    }

    /**
     * Returns true if the given tuple value structurally matches this descriptor:
     * same arity, and each element satisfies the corresponding FieldType.
     */
    public boolean structurallyMatches(SpnTupleValue tupleValue) {
        if (tupleValue.arity() != elementTypes.length) return false;
        for (int i = 0; i < elementTypes.length; i++) {
            if (!elementTypes[i].accepts(tupleValue.get(i))) return false;
        }
        return true;
    }

    public String describe() {
        var sb = new StringBuilder("(");
        for (int i = 0; i < elementTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(elementTypes[i].describe());
        }
        return sb.append(")").toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    /** Structural equality: two descriptors with the same element types are equal. */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SpnTupleDescriptor other)) return false;
        return Arrays.equals(elementTypes, other.elementTypes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(elementTypes);
    }
}
