package spn.type;

/**
 * Defines how a binary operation works on a product type by specifying an
 * expression for each component of the result.
 *
 * The componentResults array has one entry per component of the product type,
 * in the same order as the type's ComponentDescriptor array. Each expression
 * computes one component of the result from the operands' components.
 *
 * Example: Complex addition (2 components: real, imag)
 * <pre>
 *   new ProductOperationDef(Operation.ADD,
 *       ComponentExpression.add(left(0), right(0)),   // result.real
 *       ComponentExpression.add(left(1), right(1))    // result.imag
 *   )
 * </pre>
 *
 * Example: 2D vector cross product (scalar result re-wrapped as 1-component)
 * or matrix multiplication (N*N components with sum-of-products expressions).
 *
 * At runtime, SpnProductBinaryNode evaluates each expression with @ExplodeLoop,
 * producing a new Object[] of component values for the result SpnProductValue.
 */
public record ProductOperationDef(
        Operation operation,
        ComponentExpression[] componentResults
) {

    /** Varargs convenience factory. */
    public static ProductOperationDef of(Operation operation, ComponentExpression... componentResults) {
        return new ProductOperationDef(operation, componentResults);
    }

    public String describe() {
        var sb = new StringBuilder(operation.getSymbol()).append(" → [");
        for (int i = 0; i < componentResults.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(componentResults[i].describe());
        }
        return sb.append("]").toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
