package spn.stdlib.range;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.node.builtin.SpnParamHint;
import spn.node.lambda.SpnYieldContext;

/**
 * A producer that generates values by repeatedly applying a function:
 * iterate(init, f, n) yields init, f(init), f(f(init)), ... up to n values.
 *
 * <pre>
 *   stream iterate(1, doubleIt, 5) { |n|
 *     -- n takes values 1, 2, 4, 8, 16
 *   }
 * </pre>
 */
@SpnBuiltin(name = "iterate", module = "Range")
@SpnParamHint(name = "function", function = true)
@NodeInfo(shortName = "iterate")
public final class SpnIterateNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    public SpnIterateNode(CallTarget function) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(function);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        if (args.length < 3) {
            throw new SpnException("iterate requires (init, count, yieldContext)", this);
        }

        Object current = args[0];
        long count = (long) args[1];
        SpnYieldContext context = (SpnYieldContext) args[2];

        for (long i = 0; i < count; i++) {
            context.yield(current);
            current = callNode.call(current);
        }

        return 0L;
    }
}
