package spn.node.type;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.AlgebraicRule;
import spn.type.ComponentExpression;
import spn.type.Operation;
import spn.type.ProductOperationDef;
import spn.type.SpnDistinguishedElement;
import spn.type.SpnProductValue;
import spn.type.SpnTypeDescriptor;

/**
 * Executes a binary operation on product-typed values (complex numbers, vectors, etc.).
 *
 * Execution flow:
 *   1. Execute children → get SpnProductValues
 *   2. Check algebraic rules (constant-result, for element handling like Omega)
 *   3. Look up the ProductOperationDef for this operation
 *   4. Evaluate each component expression against the operands' component arrays
 *   5. Construct and return a new SpnProductValue
 *
 * KEY TRUFFLE CONCEPT: Nested @ExplodeLoop
 *
 * The component evaluation loop is @ExplodeLoop, so Graal unrolls it at compile time.
 * Since each ComponentExpression in the ProductOperationDef is a compilation constant,
 * Graal devirtualizes and inlines the entire evaluate() call chain.
 *
 * For complex multiplication (2 components, each a tree of ~4 operations):
 * the compiled code becomes roughly 6 FP multiply/add instructions. No loops,
 * no virtual dispatch, no object allocation (Graal scalar-replaces the arrays).
 *
 * AST structure:
 * <pre>
 *   SpnProductBinaryNode(Complex, MUL)
 *   ├── left:  SpnReadLocalVariableNode(z1)  → SpnProductValue(3.0, 4.0)
 *   └── right: SpnReadLocalVariableNode(z2)  → SpnProductValue(1.0, 2.0)
 * </pre>
 */
@NodeInfo(shortName = "productBinaryOp")
public final class SpnProductBinaryNode extends SpnExpressionNode {

    @Child private SpnExpressionNode leftNode;
    @Child private SpnExpressionNode rightNode;

    private final SpnTypeDescriptor typeDescriptor;
    private final Operation operation;

    @CompilationFinal(dimensions = 1)
    private final AlgebraicRule[] rules;

    @CompilationFinal(dimensions = 1)
    private final ComponentExpression[] componentExprs;

    private final int componentCount;

    public SpnProductBinaryNode(SpnExpressionNode leftNode, SpnExpressionNode rightNode,
                                SpnTypeDescriptor typeDescriptor, Operation operation) {
        this.leftNode = leftNode;
        this.rightNode = rightNode;
        this.typeDescriptor = typeDescriptor;
        this.operation = operation;
        this.rules = typeDescriptor.getRules();

        ProductOperationDef def = typeDescriptor.findProductOperation(operation);
        this.componentExprs = (def != null) ? def.componentResults() : new ComponentExpression[0];
        this.componentCount = typeDescriptor.componentCount();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object leftRaw = unwrap(leftNode.executeGeneric(frame));
        Object rightRaw = unwrap(rightNode.executeGeneric(frame));

        // Step 1: Check algebraic rules (e.g., Omega handling)
        Object ruleResult = matchRules(leftRaw, rightRaw);
        if (ruleResult != null) {
            return ruleResult;
        }

        // Step 2: Both operands must be product values
        if (!(leftRaw instanceof SpnProductValue leftProd)) {
            throw new SpnException("Expected a " + typeDescriptor.getName()
                    + " product value for left operand, got: " + leftRaw, this);
        }
        if (!(rightRaw instanceof SpnProductValue rightProd)) {
            throw new SpnException("Expected a " + typeDescriptor.getName()
                    + " product value for right operand, got: " + rightRaw, this);
        }

        // Step 3: Verify operation is defined
        if (componentExprs.length == 0) {
            throw new SpnException("Operation " + operation.getSymbol()
                    + " is not defined for product type " + typeDescriptor.getName(), this);
        }

        // Step 4: Evaluate component expressions
        return evaluateComponents(leftProd.getComponents(), rightProd.getComponents());
    }

    @ExplodeLoop
    private SpnProductValue evaluateComponents(Object[] leftComps, Object[] rightComps) {
        Object[] result = new Object[componentCount];
        for (int i = 0; i < componentExprs.length; i++) {
            result[i] = componentExprs[i].evaluate(leftComps, rightComps);
        }
        return new SpnProductValue(typeDescriptor, result);
    }

    @ExplodeLoop
    private Object matchRules(Object left, Object right) {
        for (AlgebraicRule rule : rules) {
            if (rule.matches(operation, left, right)) {
                Object result = rule.result();
                // Wrap in product value if it's an element, otherwise return as-is
                if (result instanceof SpnDistinguishedElement) {
                    return new SpnProductValue(typeDescriptor, result);
                }
                return result;
            }
        }
        return null;
    }

    private static Object unwrap(Object value) {
        if (value instanceof SpnProductValue) return value;
        return value;
    }
}
