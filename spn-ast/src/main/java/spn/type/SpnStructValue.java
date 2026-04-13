package spn.type;

import java.util.Arrays;

/**
 * An immutable runtime instance of a struct type.
 *
 * A struct value holds a reference to its descriptor (which struct type it is)
 * and an array of field values. Fields are accessed by index for performance.
 *
 * Pattern matching uses the descriptor reference to determine which struct variant
 * a value is. This is fast (pointer comparison) and unambiguous (two descriptors
 * with the same name are still different types).
 *
 * Example:
 * <pre>
 *   var circleDesc = new SpnStructDescriptor("Circle", "radius");
 *   var myCircle = new SpnStructValue(circleDesc, 5.0);
 *
 *   myCircle.getDescriptor() == circleDesc  // true → this is a Circle
 *   myCircle.get(0)                         // 5.0 → the radius
 * </pre>
 */
public final class SpnStructValue {

    private final SpnStructDescriptor descriptor;
    private final Object[] fields;

    public SpnStructValue(SpnStructDescriptor descriptor, Object... fields) {
        this.descriptor = descriptor;
        this.fields = fields;
    }

    public SpnStructDescriptor getDescriptor() {
        return descriptor;
    }

    public Object get(int index) {
        return fields[index];
    }

    public Object[] getFields() {
        return fields;
    }

    public int fieldCount() {
        return fields.length;
    }

    @Override
    public String toString() {
        String[] names = descriptor.getFieldNames();
        var sb = new StringBuilder(descriptor.getName()).append("(");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(", ");
            // Only show field name if it's a real name (not a positional _0, _1, etc.)
            if (i < names.length && !isPositionalName(names[i])) {
                sb.append(names[i]).append("=");
            }
            sb.append(fields[i]);
        }
        return sb.append(")").toString();
    }

    private static boolean isPositionalName(String name) {
        return name.length() >= 2 && name.charAt(0) == '_' && Character.isDigit(name.charAt(1));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SpnStructValue other)) return false;
        return descriptor == other.descriptor && Arrays.equals(fields, other.fields);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(fields) * 31 + System.identityHashCode(descriptor);
    }
}
