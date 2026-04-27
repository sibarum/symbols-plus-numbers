package spn.type;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes an immutable struct type -- a named constructor with typed fields.
 *
 * Structs can be fully typed, partially typed, generic, or untyped:
 *
 * <pre>
 *   // Untyped (original style, backwards compatible)
 *   var circle = new SpnStructDescriptor("Circle", "radius");
 *
 *   // Fully typed
 *   var point = SpnStructDescriptor.builder("Point")
 *       .field("x", FieldType.DOUBLE)
 *       .field("y", FieldType.DOUBLE)
 *       .build();
 *
 *   // Partially typed (mix of typed and untyped)
 *   var labeled = SpnStructDescriptor.builder("Labeled")
 *       .field("label", FieldType.STRING)
 *       .field("value")                      // untyped
 *       .build();
 *
 *   // Generic
 *   var pair = SpnStructDescriptor.builder("Pair")
 *       .typeParam("T").typeParam("U")
 *       .field("first", FieldType.generic("T"))
 *       .field("second", FieldType.generic("U"))
 *       .build();
 * </pre>
 *
 * Key properties:
 *   - Immutable: field values cannot change after construction
 *   - Nominal typing: pattern matching uses == on the descriptor reference
 *   - Fields carry type info for AST-level validation
 *   - Generic params are resolved before AST construction
 */
public final class SpnStructDescriptor {

    private final String name;

    private FieldDescriptor[] fields; // non-final: constructor fields are added at parse time
    private final String[] typeParams;

    private SpnStructDescriptor(String name, FieldDescriptor[] fields, String[] typeParams) {
        this.name = name;
        this.fields = fields;
        this.typeParams = typeParams;
    }

    /** Backwards-compatible constructor: all fields are untyped. */
    public SpnStructDescriptor(String name, String... fieldNames) {
        this.name = name;
        this.fields = new FieldDescriptor[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {
            this.fields[i] = FieldDescriptor.untyped(fieldNames[i]);
        }
        this.typeParams = new String[0];
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public String getName() {
        return name;
    }

    public FieldDescriptor[] getFields() {
        return fields;
    }

    /** Returns field names as a String array (backwards compatibility). */
    public String[] getFieldNames() {
        String[] names = new String[fields.length];
        for (int i = 0; i < fields.length; i++) {
            names[i] = fields[i].name();
        }
        return names;
    }

    public int fieldCount() {
        return fields.length;
    }

    /** Returns the index of the named field, or -1 if not found. */
    public int fieldIndex(String fieldName) {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].name().equals(fieldName)) {
                return i;
            }
        }
        return -1;
    }

    /** Returns the FieldType for field at the given index. */
    public FieldType fieldType(int index) {
        return fields[index].type();
    }

    /** Returns true if any field has a concrete type annotation (not Untyped/GenericParam). */
    public boolean hasTypedFields() {
        for (FieldDescriptor f : fields) {
            if (f.isTyped()) return true;
        }
        return false;
    }

    /** Returns true if the field at the given index is private (constructor-defined). */
    public boolean isFieldPrivate(int index) {
        return index >= 0 && index < fields.length && fields[index].isPrivate();
    }

    /**
     * Add a private field to this descriptor (parse-time only).
     * Used by {@code let this.field = expr} in constructor bodies.
     * Must be called before any Truffle compilation (i.e., during parsing).
     */
    public void addPrivateField(String fieldName, FieldType type) {
        FieldDescriptor[] newFields = java.util.Arrays.copyOf(fields, fields.length + 1);
        newFields[fields.length] = FieldDescriptor.privateField(fieldName, type);
        this.fields = newFields;
    }

    // ── Generic support ─────────────────────────────────────────────────────

    public String[] getTypeParams() {
        return typeParams;
    }

    public boolean isGeneric() {
        return typeParams.length > 0;
    }

    /**
     * Creates a new descriptor with generic type parameters replaced by concrete types.
     * The bindings map generic param names to their resolved FieldTypes.
     *
     * <pre>
     *   var pairOfIntString = pair.resolve(Map.of("T", FieldType.LONG, "U", FieldType.STRING));
     * </pre>
     */
    public SpnStructDescriptor resolve(java.util.Map<String, FieldType> bindings) {
        FieldDescriptor[] resolved = new FieldDescriptor[fields.length];
        for (int i = 0; i < fields.length; i++) {
            FieldType ft = fields[i].type();
            if (ft instanceof FieldType.GenericParam gp && bindings.containsKey(gp.name())) {
                resolved[i] = new FieldDescriptor(fields[i].name(), bindings.get(gp.name()));
            } else {
                resolved[i] = fields[i];
            }
        }
        return new SpnStructDescriptor(name, resolved, new String[0]);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder(name);
        if (typeParams.length > 0) {
            sb.append("<");
            for (int i = 0; i < typeParams.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeParams[i]);
            }
            sb.append(">");
        }
        if (fields.length > 0) {
            sb.append("(");
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(fields[i]);
            }
            sb.append(")");
        }
        return sb.toString();
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    public static final class Builder {
        private final String name;
        private final List<FieldDescriptor> fields = new ArrayList<>();
        private final List<String> typeParams = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        /** Adds a typed field. */
        public Builder field(String fieldName, FieldType type) {
            fields.add(FieldDescriptor.typed(fieldName, type));
            return this;
        }

        /** Adds an untyped field. */
        public Builder field(String fieldName) {
            fields.add(FieldDescriptor.untyped(fieldName));
            return this;
        }

        /** Adds a private typed field (defined via `let this.field` in a constructor). */
        public Builder privateField(String fieldName, FieldType type) {
            fields.add(FieldDescriptor.privateField(fieldName, type));
            return this;
        }

        /** Adds a generic type parameter name (e.g., "T", "U"). */
        public Builder typeParam(String param) {
            typeParams.add(param);
            return this;
        }

        public SpnStructDescriptor build() {
            return new SpnStructDescriptor(
                    name,
                    fields.toArray(new FieldDescriptor[0]),
                    typeParams.toArray(new String[0]));
        }
    }
}
