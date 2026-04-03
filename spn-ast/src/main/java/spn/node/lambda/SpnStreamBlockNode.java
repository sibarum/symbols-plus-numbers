package spn.node.lambda;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * The stream block: wires a producer function (that yields) to a consumer lambda
 * (that executes in the caller's frame).
 *
 * Conceptual SPN:
 * <pre>
 *   var sum = 0
 *   stream range(1, 10) { n ->
 *       sum = sum + n
 *   }
 *   // sum == 55
 * </pre>
 *
 * Execution flow:
 *   1. Create a SpnYieldContext that binds the lambda body + caller frame + param slot
 *   2. Evaluate the producer's argument expressions
 *   3. Call the producer function, passing the SpnYieldContext as an extra argument
 *   4. Inside the producer, each SpnYieldNode calls context.yield(value), which:
 *      a. Writes the value to the lambda's param slot in the CALLER's frame
 *      b. Executes the lambda body in the CALLER's frame
 *   5. When the producer returns, the stream block returns a result
 *
 * The SpnYieldContext is passed as the LAST argument to the producer. The producer's
 * frame descriptor must have a slot for it (typically the last slot). This is set up
 * by the AST builder when constructing the producer function.
 *
 * KEY INSIGHT: The lambda body executes in the caller's frame, NOT the producer's frame.
 * This is what makes it a "lambda scope" -- it can read and mutate the caller's locals
 * (like `sum` in the example above). The producer runs in its own isolated frame as
 * usual, but yield punches through to the caller's scope via the context.
 *
 * AST structure:
 * <pre>
 *   SpnStreamBlockNode
 *   ├── lambda: SpnLambdaNode(paramSlot=nSlot, body=addToSum)
 *   ├── producerTarget: range.getCallTarget()
 *   └── producerArgs: [LongLiteral(1), LongLiteral(10)]
 * </pre>
 *
 * After the stream block completes, the caller's frame has the accumulated result
 * in whatever variables the lambda mutated.
 */
@NodeInfo(shortName = "streamBlock")
public final class SpnStreamBlockNode extends SpnExpressionNode {

    @Child private SpnLambdaNode lambda;
    @Child private DirectCallNode producerCallNode;
    @Children private final SpnExpressionNode[] producerArgNodes;

    /**
     * @param lambda           the consumer code block (executes in caller's frame)
     * @param producerTarget   the producer function's CallTarget
     * @param producerArgNodes expressions for the producer's regular arguments
     *                         (the yield context is appended automatically)
     */
    public SpnStreamBlockNode(SpnLambdaNode lambda, CallTarget producerTarget,
                               SpnExpressionNode... producerArgNodes) {
        this.lambda = lambda;
        this.producerCallNode = Truffle.getRuntime().createDirectCallNode(producerTarget);
        this.producerArgNodes = producerArgNodes;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        // 1. Create the yield context linking the lambda to the caller's frame
        SpnYieldContext context = new SpnYieldContext(
                lambda.getBody(), frame, lambda.getParamSlot());

        // 2. Evaluate producer arguments + append the yield context
        Object[] args = new Object[producerArgNodes.length + 1];
        for (int i = 0; i < producerArgNodes.length; i++) {
            args[i] = producerArgNodes[i].executeGeneric(frame);
        }
        args[producerArgNodes.length] = context;

        // 3. Call the producer. It will yield values back through the context.
        Object producerResult = producerCallNode.call(args);

        return producerResult;
    }
}
