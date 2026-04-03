package spn.node.lambda;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * A lambda-scoped code block -- executes in the enclosing frame, not its own.
 *
 * Unlike a function (SpnFunctionRootNode), a lambda does NOT create a new frame.
 * It reads and writes variables in the parent scope. This makes it suitable for
 * streaming operations where the consumer accumulates results in local variables.
 *
 * A lambda is defined by:
 *   - A parameter slot (in the parent frame) where yielded values are bound
 *   - A body expression that executes in the parent frame
 *
 * Lambdas cannot be passed by reference. They exist only as the consumer half
 * of a SpnStreamBlockNode. The producer yields values, and the lambda body
 * runs for each value -- all within the caller's frame.
 *
 * Example (conceptual SPN syntax):
 * <pre>
 *   var sum = 0
 *   stream range(1, 10) { n ->    // lambda: n is the yield param, sum is in parent scope
 *       sum = sum + n
 *   }
 *   // sum is now 55
 * </pre>
 *
 * The SpnLambdaNode itself is a data holder. The actual execution happens via
 * SpnYieldContext, which the SpnStreamBlockNode creates by combining the lambda's
 * body and param slot with the caller's frame.
 */
@NodeInfo(shortName = "lambda")
public final class SpnLambdaNode extends Node {

    @Child private SpnExpressionNode body;
    private final int paramSlot;

    public SpnLambdaNode(SpnExpressionNode body, int paramSlot) {
        this.body = body;
        this.paramSlot = paramSlot;
    }

    public SpnExpressionNode getBody() {
        return body;
    }

    public int getParamSlot() {
        return paramSlot;
    }
}
