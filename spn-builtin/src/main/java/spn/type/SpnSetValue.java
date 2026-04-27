package spn.type;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An immutable, homogeneously-typed set value.
 *
 * Sets are unordered collections with no duplicates. Unlike arrays, sets have
 * no positional access -- the primary operation is membership testing (contains).
 * All "mutating" operations (union, intersection, difference, add, remove) return
 * new sets, fitting the pure function model.
 *
 * Internally backed by a LinkedHashSet for O(1) contains and predictable iteration
 * order (insertion order). For symbol-keyed sets, a future optimization could use
 * a bitset over symbol IDs for O(1) membership with minimal memory.
 *
 * <pre>
 *   // Typed set of symbols
 *   SpnSetValue.of(FieldType.SYMBOL, red, green, blue)
 *
 *   // Untyped set
 *   SpnSetValue.of(FieldType.UNTYPED, 1L, "hello", true)
 * </pre>
 */
public final class SpnSetValue {

    private final FieldType elementType;
    private final Set<Object> elements;

    private SpnSetValue(FieldType elementType, Set<Object> elements) {
        this.elementType = elementType;
        this.elements = elements;
    }

    /** Creates a set from varargs elements (deduplicates). */
    public static SpnSetValue of(FieldType elementType, Object... elements) {
        var set = new LinkedHashSet<>(Arrays.asList(elements));
        return new SpnSetValue(elementType, set);
    }

    /** Creates an empty set. */
    public static SpnSetValue empty(FieldType elementType) {
        return new SpnSetValue(elementType, new LinkedHashSet<>());
    }

    /** Creates a set from an existing Java Set (takes ownership, does not copy). */
    public static SpnSetValue wrap(FieldType elementType, Set<Object> elements) {
        return new SpnSetValue(elementType, elements);
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    public FieldType getElementType() {
        return elementType;
    }

    public boolean contains(Object element) {
        return elements.contains(element);
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /** Returns elements as an array (for iteration/streaming). */
    public Object[] toArray() {
        return elements.toArray();
    }

    // ── Functional operations (return new sets) ─────────────────────────────

    /** Returns a new set with the given element added. */
    public SpnSetValue add(Object element) {
        var newSet = new LinkedHashSet<>(elements);
        newSet.add(element);
        return new SpnSetValue(elementType, newSet);
    }

    /** Returns a new set with the given element removed. */
    public SpnSetValue remove(Object element) {
        var newSet = new LinkedHashSet<>(elements);
        newSet.remove(element);
        return new SpnSetValue(elementType, newSet);
    }

    /** Returns a new set that is the union of this and other. */
    public SpnSetValue union(SpnSetValue other) {
        var newSet = new LinkedHashSet<>(elements);
        newSet.addAll(other.elements);
        return new SpnSetValue(elementType, newSet);
    }

    /** Returns a new set containing only elements present in both sets. */
    public SpnSetValue intersection(SpnSetValue other) {
        var newSet = new LinkedHashSet<>(elements);
        newSet.retainAll(other.elements);
        return new SpnSetValue(elementType, newSet);
    }

    /** Returns a new set containing elements in this but not in other. */
    public SpnSetValue difference(SpnSetValue other) {
        var newSet = new LinkedHashSet<>(elements);
        newSet.removeAll(other.elements);
        return new SpnSetValue(elementType, newSet);
    }

    /** Returns true if this set is a subset of other. */
    public boolean isSubsetOf(SpnSetValue other) {
        return other.elements.containsAll(elements);
    }

    // ── Object methods ──────────────────────────────────────────────────────

    @Override
    public String toString() {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (Object e : elements) {
            if (!first) sb.append(", ");
            sb.append(e);
            first = false;
        }
        return sb.append("}").toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SpnSetValue other)) return false;
        return elements.equals(other.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }
}
