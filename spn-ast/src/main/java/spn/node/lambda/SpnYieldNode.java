package spn.node.lambda;

import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Yields a value from a producer to the consumer lambda.
 *
 * When executed inside a producer function that was invoked by a SpnStreamBlockNode:
 *   1. Evaluates the value expression
 *   2. Reads the SpnYieldContext from the designated frame slot
 *   3. Calls context.yield(value), which:
 *      a. Binds the value into the caller's frame at the lambda's param slot
 *      b. Executes the lambda body in the caller's frame
 *      c. Returns the lambda body's result
 *   4. Returns the lambda's result (available to the producer if needed)
 *
 * The yield context slot is set by SpnStreamBlockNode before invoking the producer.
 * If no yield context is present (the function was called outside a stream block),
 * this node throws an error.
 *
 * Usage in a producer function:
 * <pre>
 *   // range(start, end) yields each integer from start to end
 *   // Body: while (i < end) { yield i; i = i + 1 }
 *   new SpnYieldNode(readI, yieldContextSlot)
 * </pre>
 */
@NodeInfo(shortName = "yield")
public final class SpnYieldNode extends SpnExpressionNode {

    @Child private SpnExpressionNode valueNode;
    private final int yieldContextSlot;

    public SpnYieldNode(SpnExpressionNode valueNode, int yieldContextSlot) {
        this.valueNode = valueNode;
        this.yieldContextSlot = yieldContextSlot;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = valueNode.executeGeneric(frame);

        Object ctxObj;
        try {
            ctxObj = frame.getObject(yieldContextSlot);
        } catch (FrameSlotTypeException e) {
            throw new SpnException(
                    "yield used outside of a stream block -- no yield context available", this);
        }

        if (!(ctxObj instanceof SpnYieldContext ctx)) {
            throw new SpnException(
                    "yield used outside of a stream block -- no yield context available", this);
        }

        return ctx.yield(value);
    }
}
