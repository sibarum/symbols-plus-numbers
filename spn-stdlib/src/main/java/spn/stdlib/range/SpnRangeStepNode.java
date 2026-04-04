package spn.stdlib.range;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.node.lambda.SpnYieldContext;

/**
 * A range producer with configurable step: rangeStep(start, end, step)
 *
 * Works with the stream block mechanism -- yields each value to the consumer lambda
 * via SpnYieldContext (passed as the last argument).
 *
 * <pre>
 *   stream rangeStep(0, 10, 2) { |n|
 *     -- n takes values 0, 2, 4, 6, 8
 *   }
 * </pre>
 */
@SpnBuiltin(name = "rangeStep", module = "Range")
@NodeInfo(shortName = "rangeStep")
public final class SpnRangeStepNode extends SpnExpressionNode {

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        if (args.length < 4) {
            throw new SpnException("rangeStep requires (start, end, step, yieldContext)", this);
        }

        long start = (long) args[0];
        long end = (long) args[1];
        long step = (long) args[2];
        SpnYieldContext context = (SpnYieldContext) args[3];

        if (step == 0) {
            throw new SpnException("rangeStep: step cannot be zero", this);
        }

        if (step > 0) {
            for (long i = start; i < end; i += step) {
                context.yield(i);
            }
        } else {
            for (long i = start; i > end; i += step) {
                context.yield(i);
            }
        }

        return 0L;
    }
}
