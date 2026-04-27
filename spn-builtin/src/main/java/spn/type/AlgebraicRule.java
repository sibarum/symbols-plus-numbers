package spn.type;

/**
 * An algebraic rule that rewrites an operation for specific operand patterns.
 *
 * When a constrained binary operation is executed, rules are checked BEFORE the normal
 * operation. If a rule matches, its result is returned directly -- the normal operation
 * is never performed. This is critical for rules like "n / 0 = Omega" where the normal
 * operation would throw an error.
 *
 * Rules are checked in order, so more specific rules should come before general ones.
 * The first matching rule wins.
 *
 * Example definitions:
 * <pre>
 *   var omega = new SpnDistinguishedElement("Omega");
 *
 *   // n / 0 = Omega  (any number divided by zero yields Omega)
 *   new AlgebraicRule(Operation.DIV, new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega)
 *
 *   // Omega + n = Omega  (Omega absorbs addition)
 *   new AlgebraicRule(Operation.ADD, new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega)
 *
 *   // n + Omega = Omega  (commutativity of absorption)
 *   new AlgebraicRule(Operation.ADD, new OperandPattern.Any(), new OperandPattern.IsElement(omega), omega)
 * </pre>
 *
 * Truffle performance note:
 * Rules are stored in a @CompilationFinal(dimensions = 1) array. With @ExplodeLoop,
 * each rule becomes a constant at compile time. The matches() call chain:
 *   rule.matches() → left.matches() → right.matches()
 * is fully inlined because every object in the chain is a compilation constant.
 * The compiled code for "n / 0 = Omega" becomes roughly:
 *   if (right instanceof Long l && l == 0) return omega;
 */
public record AlgebraicRule(
        Operation operation,
        OperandPattern left,
        OperandPattern right,
        Object result
) {

    /**
     * Returns true if this rule applies to the given operation and operand values.
     */
    public boolean matches(Operation op, Object leftValue, Object rightValue) {
        return this.operation == op
                && this.left.matches(leftValue)
                && this.right.matches(rightValue);
    }

    /**
     * Human-readable description, e.g., "n / 0 = Omega".
     */
    public String describe() {
        return left.describe() + " " + operation.getSymbol() + " " + right.describe()
                + " = " + result;
    }

    @Override
    public String toString() {
        return describe();
    }
}
