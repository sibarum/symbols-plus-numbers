package spn.node.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Less-than comparison. Returns a boolean.
 *
 * Note: no "rewriteOn" here because comparison never overflows -- it just produces
 * a boolean. The specialization pattern is simpler than arithmetic.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "<")
public abstract class SpnLessThanNode extends SpnExpressionNode {

    @Specialization
    protected boolean lessThanLongs(long left, long right) {
        return left < right;
    }

    @Specialization
    protected boolean lessThanDoubles(double left, double right) {
        return left < right;
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Cannot compare " + left.getClass().getSimpleName()
                + " and " + right.getClass().getSimpleName(), this);
    }
}
