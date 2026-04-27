package spn.type;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes a function's signature: name, purity, parameter tuple, and return type.
 *
 * Pure functions are referentially transparent -- same inputs always produce the
 * same output with no side effects. SPN enforces this distinction at the type level.
 *
 * Parameters form a tuple: an ordered sequence of optionally-typed positions.
 * This means function arity and parameter types are part of the function's identity.
 *
 * <pre>
 *   // area : Shape -> Double
 *   SpnFunctionDescriptor.pure("area")
 *       .param("shape")
 *       .returns(FieldType.DOUBLE)
 *       .build();
 *
 *   // add : (Long, Long) -> Long
 *   SpnFunctionDescriptor.pure("add")
 *       .param("a", FieldType.LONG)
 *       .param("b", FieldType.LONG)
 *       .returns(FieldType.LONG)
 *       .build();
 *
 *   // identity : T -> T  (untyped params and return)
 *   SpnFunctionDescriptor.pure("identity")
 *       .param("x")
 *       .build();
 * </pre>
 */
public final class SpnFunctionDescriptor {

    private final String name;
    private final boolean pure;
    private final FieldDescriptor[] params;
    private final FieldType returnType;
    private final boolean variadic;
    /** Element type of the variadic tail as declared by the user (before
     *  the param slot was swapped to UntypedArray for runtime). Null when
     *  the function is not variadic. Kept as a side channel so dispatch
     *  can type-check tail args against T while the param's runtime type
     *  is the packed-array shape. */
    private final FieldType variadicElementType;

    private SpnFunctionDescriptor(String name, boolean pure,
                                   FieldDescriptor[] params, FieldType returnType,
                                   boolean variadic, FieldType variadicElementType) {
        this.name = name;
        this.pure = pure;
        this.params = params;
        this.returnType = returnType;
        this.variadic = variadic;
        this.variadicElementType = variadicElementType;
    }

    /** Starts building a pure function descriptor. */
    public static Builder pure(String name) {
        return new Builder(name, true);
    }

    /** Starts building an impure function descriptor (for future use). */
    public static Builder impure(String name) {
        return new Builder(name, false);
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public String getName() {
        return name;
    }

    public boolean isPure() {
        return pure;
    }

    public FieldDescriptor[] getParams() {
        return params;
    }

    public int arity() {
        return params.length;
    }

    /** True if the last parameter is variadic (declared as {@code T...}).
     *  Variadic functions accept any number of trailing args (zero or more);
     *  those args are packed into an array and handed to the function as its
     *  final parameter value. */
    public boolean isVariadic() {
        return variadic;
    }

    /** Number of fixed (non-variadic) parameters. For non-variadic
     *  functions this equals {@link #arity()}. For variadic, it's
     *  {@code arity() - 1} — everything before the trailing {@code T...}. */
    public int fixedArity() {
        return variadic ? params.length - 1 : params.length;
    }

    /** Element type of the variadic tail as declared (e.g. {@code int} in
     *  {@code (int...)}), or null if not variadic. Distinct from the
     *  variadic param's {@link FieldDescriptor#type()}, which reflects the
     *  runtime slot type (UntypedArray). */
    public FieldType variadicElementType() {
        return variadicElementType;
    }

    public FieldType getReturnType() {
        return returnType;
    }

    /** Returns the FieldType for parameter at the given index. */
    public FieldType paramType(int index) {
        return params[index].type();
    }

    /** Returns true if any parameter has a concrete type annotation. */
    public boolean hasTypedParams() {
        for (FieldDescriptor p : params) {
            if (p.isTyped()) return true;
        }
        return false;
    }

    /** Returns true if the return type is concrete (not Untyped). */
    public boolean hasTypedReturn() {
        return !(returnType instanceof FieldType.Untyped);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        if (pure) sb.append("pure ");
        sb.append(name).append("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i]);
        }
        sb.append(")");
        if (hasTypedReturn()) {
            sb.append(" -> ").append(returnType.describe());
        }
        return sb.toString();
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    public static final class Builder {
        private final String name;
        private final boolean pure;
        private final List<FieldDescriptor> params = new ArrayList<>();
        private FieldType returnType = FieldType.UNTYPED;
        private boolean variadic = false;
        private FieldType variadicElementType = null;

        private Builder(String name, boolean pure) {
            this.name = name;
            this.pure = pure;
        }

        /** Adds a typed parameter. */
        public Builder param(String paramName, FieldType type) {
            params.add(FieldDescriptor.typed(paramName, type));
            return this;
        }

        /** Adds an untyped parameter. */
        public Builder param(String paramName) {
            params.add(FieldDescriptor.untyped(paramName));
            return this;
        }

        /** Sets the return type. */
        public Builder returns(FieldType type) {
            this.returnType = type;
            return this;
        }

        /** Marks the last parameter as variadic. The last param's declared
         *  type is remembered as the variadic element type and its runtime
         *  slot type is replaced with UntypedArray — at call time the packed
         *  tail arrives as an array, element-matching happens at dispatch. */
        public Builder variadic() {
            if (params.isEmpty()) {
                throw new IllegalStateException(
                        "variadic() requires at least one parameter");
            }
            this.variadic = true;
            int last = params.size() - 1;
            FieldDescriptor tail = params.get(last);
            this.variadicElementType = tail.type();
            params.set(last, FieldDescriptor.typed(
                    tail.name(), FieldType.ofArray(FieldType.UNTYPED)));
            return this;
        }

        public SpnFunctionDescriptor build() {
            return new SpnFunctionDescriptor(
                    name, pure,
                    params.toArray(new FieldDescriptor[0]),
                    returnType,
                    variadic,
                    variadicElementType);
        }
    }
}
