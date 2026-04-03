package spn.node.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Multiplication operator with long overflow detection and double fallback.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "*")
public abstract class SpnMultiplyNode extends SpnExpressionNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long multiplyLongs(long left, long right) {
        return Math.multiplyExact(left, right);
    }

    @Specialization(replaces = "multiplyLongs")
    protected double multiplyDoubles(double left, double right) {
        return left * right;
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Cannot multiply " + left.getClass().getSimpleName()
                + " and " + right.getClass().getSimpleName(), this);
    }
}
