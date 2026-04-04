package spn.stdlib.range;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.node.lambda.SpnYieldContext;

/**
 * A producer that yields a value n times: repeat(value, n)
 *
 * <pre>
 *   stream repeat(:hello, 3) { |v|
 *     -- v is :hello, three times
 *   }
 * </pre>
 */
@SpnBuiltin(name = "repeat", module = "Range")
@NodeInfo(shortName = "repeat")
public final class SpnRepeatNode extends SpnExpressionNode {

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        if (args.length < 3) {
            throw new SpnException("repeat requires (value, count, yieldContext)", this);
        }

        Object value = args[0];
        long count = (long) args[1];
        SpnYieldContext context = (SpnYieldContext) args[2];

        for (long i = 0; i < count; i++) {
            context.yield(value);
        }

        return 0L;
    }
}
