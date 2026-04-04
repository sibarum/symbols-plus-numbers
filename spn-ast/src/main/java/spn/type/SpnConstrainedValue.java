package spn.type;

/**
 * A runtime value tagged with its constrained type.
 *
 * When a value is checked against a type's constraints and passes, it gets wrapped
 * in a SpnConstrainedValue. This wrapper travels through the AST so that:
 *
 *   1. Operations can detect that they're working with constrained values
 *   2. The result type can be propagated (Natural + Natural → Natural)
 *   3. The unwrap/compute/check cycle can be applied at each operation
 *
 * The AST pattern for constrained arithmetic:
 * <pre>
 *   SpnCheckConstraintNode(Natural)        ← check result, wrap
 *   └── SpnAddNode                         ← operate on raw values
 *       ├── SpnUnwrapConstrainedNode       ← unwrap left to raw long/double
 *       │   └── SpnReadLocalVariableNode   ← read constrained value
 *       └── SpnUnwrapConstrainedNode       ← unwrap right to raw long/double
 *           └── SpnReadLocalVariableNode   ← read constrained value
 * </pre>
 *
 * This keeps the existing arithmetic nodes untouched -- they work on raw values
 * as before. The constrained behavior is layered on via composition, which is
 * the idiomatic Truffle approach.
 *
 * Truffle performance note:
 * Yes, this wraps a primitive in an object (boxing). But because SpnTypeDescriptor
 * is a compilation constant, Graal's escape analysis can often scalar-replace the
 * SpnConstrainedValue entirely -- the wrapper never actually gets heap-allocated
 * in the compiled code. The value stays in a register and the type check is inlined.
 */
public final class SpnConstrainedValue {

    private final Object value;
    private final SpnTypeDescriptor type;

    public SpnConstrainedValue(Object value, SpnTypeDescriptor type) {
        this.value = value;
        this.type = type;
    }

    /** The raw unwrapped value (Long, Double, Boolean, String, etc.). */
    public Object getValue() {
        return value;
    }

    /** The type descriptor this value has been validated against. */
    public SpnTypeDescriptor getType() {
        return type;
    }

    @Override
    public String toString() {
        return value + " : " + type.getName();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof SpnConstrainedValue other)) return false;
        return type == other.type && java.util.Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hashCode(value) * 31 + System.identityHashCode(type);
    }
}
