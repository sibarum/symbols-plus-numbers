package spn.node.expr;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Subtraction operator with long overflow detection and double fallback.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "-")
public abstract class SpnSubtractNode extends SpnExpressionNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long subLongs(long left, long right) {
        return Math.subtractExact(left, right);
    }

    @Specialization(replaces = "subLongs")
    protected double subDoubles(double left, double right) {
        return left - right;
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Cannot subtract " + SpnTypeName.of(left)
                + " and " + SpnTypeName.of(right), this);
    }
}
