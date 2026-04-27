package spn.type;

/**
 * An expression tree that computes one component of a product operation's result.
 *
 * When a product type defines an operation (e.g., complex multiplication), each
 * component of the result is described by a ComponentExpression. At runtime, the
 * expression is evaluated with the left and right operands' component arrays.
 *
 * Example: Complex multiplication  (a+bi)(c+di) = (ac-bd) + (ad+bc)i
 * <pre>
 *   import static spn.type.ComponentExpression.*;
 *
 *   // result.real = left.real * right.real - left.imag * right.imag
 *   var realPart = sub(mul(left(0), right(0)), mul(left(1), right(1)));
 *
 *   // result.imag = left.real * right.imag + left.imag * right.real
 *   var imagPart = add(mul(left(0), right(1)), mul(left(1), right(0)));
 * </pre>
 *
 * Truffle performance:
 * Expression trees are stored in @CompilationFinal arrays inside ProductOperationDef.
 * Since each node in the tree is a constant, Graal devirtualizes evaluate() calls and
 * inlines the entire computation. A complex multiplication compiles to ~6 FP instructions.
 *
 * The expression tree is also inspectable as data, which the consistency checker can
 * analyze (e.g., verifying component index bounds).
 */
public sealed interface ComponentExpression {

    /**
     * Evaluates this expression given the component arrays of the two operands.
     */
    Object evaluate(Object[] leftComponents, Object[] rightComponents);

    /** Human-readable rendering of this expression. */
    String describe();

    // ── Terminal expressions ────────────────────────────────────────────────

    /** References component [index] of the left operand. */
    record Left(int index) implements ComponentExpression {
        @Override
        public Object evaluate(Object[] l, Object[] r) {
            return l[index];
        }

        @Override
        public String describe() {
            return "left[" + index + "]";
        }
    }

    /** References component [index] of the right operand. */
    record Right(int index) implements ComponentExpression {
        @Override
        public Object evaluate(Object[] l, Object[] r) {
            return r[index];
        }

        @Override
        public String describe() {
            return "right[" + index + "]";
        }
    }

    /** A constant value. */
    record Const(Object value) implements ComponentExpression {
        @Override
        public Object evaluate(Object[] l, Object[] r) {
            return value;
        }

        @Override
        public String describe() {
            return String.valueOf(value);
        }
    }

    // ── Composite expressions ───────────────────────────────────────────────

    /** A binary operation applied to two sub-expressions. Reuses Operation.perform(). */
    record BinaryOp(Operation op, ComponentExpression a, ComponentExpression b)
            implements ComponentExpression {
        @Override
        public Object evaluate(Object[] l, Object[] r) {
            return op.perform(a.evaluate(l, r), b.evaluate(l, r));
        }

        @Override
        public String describe() {
            return "(" + a.describe() + " " + op.getSymbol() + " " + b.describe() + ")";
        }
    }

    /** Negation of a sub-expression. */
    record Neg(ComponentExpression operand) implements ComponentExpression {
        @Override
        public Object evaluate(Object[] l, Object[] r) {
            Object val = operand.evaluate(l, r);
            return switch (val) {
                case Long n -> -n;
                case Double d -> -d;
                default -> throw new ArithmeticException(
                        "Cannot negate " + val.getClass().getSimpleName());
            };
        }

        @Override
        public String describe() {
            return "-(" + operand.describe() + ")";
        }
    }

    // ── Static factory methods (import static spn.type.ComponentExpression.*) ──

    static ComponentExpression left(int index)           { return new Left(index); }
    static ComponentExpression right(int index)          { return new Right(index); }
    static ComponentExpression constant(Object value)    { return new Const(value); }
    static ComponentExpression constant(long value)      { return new Const(value); }
    static ComponentExpression constant(double value)    { return new Const(value); }

    static ComponentExpression add(ComponentExpression a, ComponentExpression b) {
        return new BinaryOp(Operation.ADD, a, b);
    }

    static ComponentExpression sub(ComponentExpression a, ComponentExpression b) {
        return new BinaryOp(Operation.SUB, a, b);
    }

    static ComponentExpression mul(ComponentExpression a, ComponentExpression b) {
        return new BinaryOp(Operation.MUL, a, b);
    }

    static ComponentExpression div(ComponentExpression a, ComponentExpression b) {
        return new BinaryOp(Operation.DIV, a, b);
    }

    static ComponentExpression neg(ComponentExpression operand) {
        return new Neg(operand);
    }
}
