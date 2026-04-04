package spn.node.type;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.AlgebraicRule;
import spn.type.Constraint;
import spn.type.Operation;
import spn.type.SpnConstrainedValue;
import spn.type.SpnDistinguishedElement;
import spn.type.SpnTypeDescriptor;

/**
 * A binary operation on constrained values that respects algebraic rules.
 *
 * This node handles the complete lifecycle of a constrained binary operation:
 *
 *   1. Execute children → get operand values
 *   2. Unwrap SpnConstrainedValues to raw values
 *   3. Check algebraic rules → if a rule matches, return rule's result (wrapped)
 *   4. If an operand is a distinguished element and no rule matched → error
 *   5. Perform the normal operation (via Operation.perform)
 *   6. Check constraints on the result
 *   7. Wrap result in SpnConstrainedValue and return
 *
 * This replaces the layer-1 four-node composition (unwrap → op → check → wrap) for
 * types that have algebraic rules. The key reason: rules must intercept BEFORE the
 * operation executes (e.g., "n / 0 = Omega" must fire before division-by-zero throws).
 *
 * The layer-1 composition pattern still works for types without rules.
 *
 * AST structure:
 * <pre>
 *   SpnConstrainedBinaryNode(ExtendedNatural, DIV)
 *   ├── left:  SpnReadLocalVariableNode(x)   → returns SpnConstrainedValue
 *   └── right: SpnReadLocalVariableNode(y)   → returns SpnConstrainedValue
 * </pre>
 *
 * Truffle performance:
 * Operation, rules[], and constraints[] are all compilation-final. After Graal compiles:
 *   - The Operation enum dispatch is a direct call (constant enum)
 *   - Each rule.matches() chain is inlined (constant rule + constant patterns)
 *   - Each constraint.check() is inlined (constant constraint)
 *   - The only variable parts are the operand values themselves
 *
 * Example: for ExtendedNatural with rule "n / 0 = Omega" and constraints n >= 0, n % 1 == 0:
 * <pre>
 *   // Compiled code (conceptually):
 *   long left = unwrap(executeLeft());
 *   long right = unwrap(executeRight());
 *   if (right == 0) return wrap(OMEGA);          // rule: n / 0 = Omega
 *   long result = left / right;                   // normal division
 *   if (result < 0) throw violation("n >= 0");    // constraint check
 *   if (result % 1 != 0) throw violation("n%1");  // constraint check
 *   return wrap(result);
 * </pre>
 */
@NodeInfo(shortName = "constrainedBinaryOp")
public final class SpnConstrainedBinaryNode extends SpnExpressionNode {

    @Child private SpnExpressionNode leftNode;
    @Child private SpnExpressionNode rightNode;

    private final SpnTypeDescriptor typeDescriptor;
    private final Operation operation;

    @CompilationFinal(dimensions = 1)
    private final AlgebraicRule[] rules;

    @CompilationFinal(dimensions = 1)
    private final Constraint[] constraints;

    public SpnConstrainedBinaryNode(SpnExpressionNode leftNode, SpnExpressionNode rightNode,
                                    SpnTypeDescriptor typeDescriptor, Operation operation) {
        this.leftNode = leftNode;
        this.rightNode = rightNode;
        this.typeDescriptor = typeDescriptor;
        this.operation = operation;
        this.rules = typeDescriptor.getRules();
        this.constraints = typeDescriptor.getConstraints();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object leftRaw = executeAndUnwrap(leftNode, frame);
        Object rightRaw = executeAndUnwrap(rightNode, frame);

        // Step 1: Check algebraic rules (order matters -- first match wins)
        Object ruleResult = matchRules(leftRaw, rightRaw);
        if (ruleResult != null) {
            return new SpnConstrainedValue(ruleResult, typeDescriptor);
        }

        // Step 2: If either operand is a distinguished element and no rule handled it,
        // that's an error -- we can't perform normal arithmetic on special elements.
        if (leftRaw instanceof SpnDistinguishedElement || rightRaw instanceof SpnDistinguishedElement) {
            throw new SpnException(
                    "No rule defined for " + leftRaw + " " + operation.getSymbol() + " " + rightRaw
                            + " in type " + typeDescriptor.getName(),
                    this);
        }

        // Step 3: Perform the normal operation
        Object result;
        try {
            result = operation.perform(leftRaw, rightRaw);
        } catch (ArithmeticException e) {
            throw new SpnException(
                    e.getMessage() + " in " + operation.getSymbol()
                            + " operation on type " + typeDescriptor.getName(),
                    this);
        }

        // Step 4: Check constraints on the result
        checkConstraints(result);

        return new SpnConstrainedValue(result, typeDescriptor);
    }

    /**
     * Matches the operands against all algebraic rules.
     * Returns the rule's result if a match is found, null otherwise.
     */
    @ExplodeLoop
    private Object matchRules(Object left, Object right) {
        for (AlgebraicRule rule : rules) {
            if (rule.matches(operation, left, right)) {
                return rule.result();
            }
        }
        return null;
    }

    /**
     * Validates the computed result against all type constraints.
     */
    @ExplodeLoop
    private void checkConstraints(Object result) {
        for (Constraint constraint : constraints) {
            if (!constraint.check(result)) {
                throw new SpnException(
                        "Operation result " + result + " violates constraint '"
                                + typeDescriptor.describeConstraint(constraint)
                                + "' of type " + typeDescriptor.getName(),
                        this);
            }
        }
    }

    /**
     * Executes a child and unwraps it if it's a SpnConstrainedValue.
     * The raw value may be a Long, Double, String, or SpnDistinguishedElement.
     */
    private static Object executeAndUnwrap(SpnExpressionNode node, VirtualFrame frame) {
        Object value = node.executeGeneric(frame);
        if (value instanceof SpnConstrainedValue constrained) {
            return constrained.getValue();
        }
        return value;
    }
}
