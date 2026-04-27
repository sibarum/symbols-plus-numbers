package spn.type;

import java.util.Arrays;

/**
 * An immutable, homogeneously-typed array value.
 *
 * Once constructed, elements cannot be changed. "Updates" produce new arrays
 * (functional/persistent style), fitting the pure function model.
 *
 * Each array carries its element FieldType, which was validated at construction time.
 * This means downstream consumers can trust the element types without re-checking.
 *
 * <pre>
 *   // Typed array of longs
 *   new SpnArrayValue(FieldType.LONG, 1L, 2L, 3L)
 *
 *   // Untyped array (any elements)
 *   new SpnArrayValue(FieldType.UNTYPED, 1L, "hello", true)
 * </pre>
 *
 * Key operations:
 *   - get(index): access by 0-based index
 *   - length(): number of elements
 *   - tail(): new array without the first element (for head/tail decomposition)
 *   - slice(from, to): sub-array [from, to)
 */
public final class SpnArrayValue {

    private final FieldType elementType;
    private final Object[] elements;

    public SpnArrayValue(FieldType elementType, Object... elements) {
        this.elementType = elementType;
        this.elements = elements;
    }

    public FieldType getElementType() {
        return elementType;
    }

    public Object get(int index) {
        return elements[index];
    }

    public Object[] getElements() {
        return elements;
    }

    public int length() {
        return elements.length;
    }

    public boolean isEmpty() {
        return elements.length == 0;
    }

    /** Returns the first element. */
    public Object head() {
        return elements[0];
    }

    /** Returns a new array without the first element. */
    public SpnArrayValue tail() {
        return new SpnArrayValue(elementType,
                Arrays.copyOfRange(elements, 1, elements.length));
    }

    /** Returns a sub-array [from, to). */
    public SpnArrayValue slice(int from, int to) {
        return new SpnArrayValue(elementType,
                Arrays.copyOfRange(elements, from, to));
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(elements[i]);
        }
        return sb.append("]").toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SpnArrayValue other)) return false;
        return Arrays.equals(elements, other.elements);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(elements);
    }
}
