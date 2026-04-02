package spn.type;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * Describes an immutable struct type -- a named constructor with named fields.
 *
 * Structs are the building blocks of algebraic data types (ADTs) in SPN.
 * Multiple struct descriptors can serve as variants of a single conceptual type,
 * enabling Haskell-style sum types with pattern matching:
 *
 * <pre>
 *   // data Shape = Circle radius | Rectangle width height | Triangle a b c
 *   var circle    = new SpnStructDescriptor("Circle", "radius");
 *   var rectangle = new SpnStructDescriptor("Rectangle", "width", "height");
 *   var triangle  = new SpnStructDescriptor("Triangle", "a", "b", "c");
 * </pre>
 *
 * Key properties:
 *   - Immutable: once a SpnStructValue is created, its fields never change
 *   - Identity by reference: pattern matching uses == on the descriptor, so
 *     two descriptors with the same name are NOT the same type
 *   - Fields are ordered: index 0 is the first field, etc.
 *   - Field names are for human readability and DSL access; runtime uses indices
 */
public final class SpnStructDescriptor {

    private final String name;

    @CompilationFinal(dimensions = 1)
    private final String[] fieldNames;

    public SpnStructDescriptor(String name, String... fieldNames) {
        this.name = name;
        this.fieldNames = fieldNames;
    }

    public String getName() {
        return name;
    }

    public String[] getFieldNames() {
        return fieldNames;
    }

    public int fieldCount() {
        return fieldNames.length;
    }

    /**
     * Returns the index of the named field, or -1 if not found.
     */
    public int fieldIndex(String fieldName) {
        for (int i = 0; i < fieldNames.length; i++) {
            if (fieldNames[i].equals(fieldName)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        if (fieldNames.length == 0) return name;
        var sb = new StringBuilder(name).append("(");
        for (int i = 0; i < fieldNames.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fieldNames[i]);
        }
        return sb.append(")").toString();
    }
}
