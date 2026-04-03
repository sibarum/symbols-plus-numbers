package spn.type;

/**
 * The type annotation on a struct field or tuple position.
 *
 * Each variant knows how to check whether a runtime value is acceptable.
 * FieldTypes are stored as @CompilationFinal data in AST nodes, so Graal
 * devirtualizes accepts() into a single instanceof + pointer comparison.
 *
 * Specificity spectrum:
 *   Untyped        → accepts anything
 *   GenericParam    → placeholder, accepts anything until resolved
 *   OfClass         → accepts a specific primitive wrapper (Long, Double, etc.)
 *   OfStruct        → accepts SpnStructValue of a specific descriptor (nominal)
 *   OfConstrainedType → accepts SpnConstrainedValue of a specific type
 *   OfProduct       → accepts SpnProductValue of a specific type
 *   OfTuple         → accepts SpnTupleValue matching a specific tuple descriptor (structural)
 *
 * Example: a typed struct definition
 * <pre>
 *   SpnStructDescriptor.builder("Point")
 *       .field("x", FieldType.DOUBLE)
 *       .field("y", FieldType.DOUBLE)
 *       .build();
 * </pre>
 *
 * Example: a mixed-specificity tuple
 * <pre>
 *   new SpnTupleDescriptor(FieldType.LONG, FieldType.UNTYPED, FieldType.DOUBLE)
 *   // position 0: must be Long, position 1: anything, position 2: must be Double
 * </pre>
 */
public sealed interface FieldType {

    /** Returns true if the given runtime value is acceptable for this field type. */
    boolean accepts(Object value);

    /** Human-readable description for error messages. */
    String describe();

    // ── Convenience constants ───────────────────────────────────────────────

    Untyped UNTYPED = new Untyped();
    OfClass SYMBOL = new OfClass(SpnSymbol.class, "Symbol");
    OfClass LONG = new OfClass(Long.class, "Long");
    OfClass DOUBLE = new OfClass(Double.class, "Double");
    OfClass BOOLEAN = new OfClass(Boolean.class, "Boolean");
    OfClass STRING = new OfClass(String.class, "String");

    // ── Convenience factories ───────────────────────────────────────────────

    static FieldType ofStruct(SpnStructDescriptor descriptor) {
        return new OfStruct(descriptor);
    }

    static FieldType ofConstrainedType(SpnTypeDescriptor descriptor) {
        return new OfConstrainedType(descriptor);
    }

    static FieldType ofProduct(SpnTypeDescriptor descriptor) {
        return new OfProduct(descriptor);
    }

    static FieldType ofTuple(SpnTupleDescriptor descriptor) {
        return new OfTuple(descriptor);
    }

    static FieldType ofArray(FieldType elementType) {
        return new OfArray(elementType);
    }

    static FieldType ofSet(FieldType elementType) {
        return new OfSet(elementType);
    }

    static FieldType ofDictionary(FieldType valueType) {
        return new OfDictionary(valueType);
    }

    static FieldType generic(String name) {
        return new GenericParam(name);
    }

    // ── Variants ────────────────────────────────────────────────────────────

    /** Accepts any value. The "don't care" type. */
    record Untyped() implements FieldType {
        @Override public boolean accepts(Object value) { return true; }
        @Override public String describe() { return "_"; }
    }

    /** Accepts values that are instances of a specific Java class (Long, Double, etc.). */
    record OfClass(Class<?> javaType, String displayName) implements FieldType {
        @Override
        public boolean accepts(Object value) {
            return javaType.isInstance(value);
        }

        @Override
        public String describe() {
            return displayName;
        }
    }

    /** Accepts SpnStructValue instances of a specific struct descriptor (nominal typing). */
    record OfStruct(SpnStructDescriptor descriptor) implements FieldType {
        @Override
        public boolean accepts(Object value) {
            return value instanceof SpnStructValue sv && sv.getDescriptor() == descriptor;
        }

        @Override
        public String describe() {
            return descriptor.getName();
        }
    }

    /** Accepts SpnConstrainedValue instances of a specific constrained type. */
    record OfConstrainedType(SpnTypeDescriptor descriptor) implements FieldType {
        @Override
        public boolean accepts(Object value) {
            return value instanceof SpnConstrainedValue cv && cv.getType() == descriptor;
        }

        @Override
        public String describe() {
            return descriptor.getName();
        }
    }

    /** Accepts SpnProductValue instances of a specific product type. */
    record OfProduct(SpnTypeDescriptor descriptor) implements FieldType {
        @Override
        public boolean accepts(Object value) {
            return value instanceof SpnProductValue pv && pv.getType() == descriptor;
        }

        @Override
        public String describe() {
            return descriptor.getName();
        }
    }

    /** Accepts SpnTupleValue instances that structurally match a tuple descriptor. */
    record OfTuple(SpnTupleDescriptor descriptor) implements FieldType {
        @Override
        public boolean accepts(Object value) {
            if (!(value instanceof SpnTupleValue tv)) return false;
            return descriptor.structurallyMatches(tv);
        }

        @Override
        public String describe() {
            return descriptor.describe();
        }
    }

    /**
     * Accepts SpnArrayValue instances with a compatible element type.
     *
     * If our expected elementType is UNTYPED, any array matches.
     * Otherwise the array's element type must match exactly (invariant).
     */
    record OfArray(FieldType elementType) implements FieldType {
        @Override
        public boolean accepts(Object value) {
            if (!(value instanceof SpnArrayValue arr)) return false;
            if (elementType instanceof Untyped) return true;
            return elementType.equals(arr.getElementType());
        }

        @Override
        public String describe() {
            if (elementType instanceof Untyped) return "Array";
            return "Array<" + elementType.describe() + ">";
        }
    }

    /**
     * Accepts SpnSetValue instances with a compatible element type.
     */
    record OfSet(FieldType elementType) implements FieldType {
        @Override
        public boolean accepts(Object value) {
            if (!(value instanceof SpnSetValue sv)) return false;
            if (elementType instanceof Untyped) return true;
            return elementType.equals(sv.getElementType());
        }

        @Override
        public String describe() {
            if (elementType instanceof Untyped) return "Set";
            return "Set<" + elementType.describe() + ">";
        }
    }

    /**
     * Accepts SpnDictionaryValue instances with a compatible value type.
     * Keys are always symbols (SpnSymbol).
     */
    record OfDictionary(FieldType valueType) implements FieldType {
        @Override
        public boolean accepts(Object value) {
            if (!(value instanceof SpnDictionaryValue dv)) return false;
            if (valueType instanceof Untyped) return true;
            return valueType.equals(dv.getValueType());
        }

        @Override
        public String describe() {
            if (valueType instanceof Untyped) return "Dict";
            return "Dict<" + valueType.describe() + ">";
        }
    }

    /**
     * An unresolved generic type parameter. Accepts anything until resolved.
     *
     * Generic params exist in struct definitions like Pair&lt;T, U&gt;. When the
     * struct is instantiated with concrete types, GenericParam("T") is replaced
     * with the actual FieldType (e.g., FieldType.LONG). This resolution happens
     * before AST construction -- the AST only sees concrete types.
     *
     * In an unresolved state (e.g., during definition analysis), GenericParam
     * behaves like Untyped.
     */
    record GenericParam(String name) implements FieldType {
        @Override
        public boolean accepts(Object value) {
            return true; // unresolved = accept anything
        }

        @Override
        public String describe() {
            return name;
        }
    }
}
