package spn.node.expr;

import spn.language.SpnTypeName;
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

    @Specialization
    protected long multiplyLongs(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException e) {
            throw new SpnException("long overflow: " + left + " * " + right, this);
        }
    }

    @Specialization
    protected double multiplyDoubles(double left, double right) {
        return left * right;
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Type error: *(" + SpnTypeName.of(left)
                + ", " + SpnTypeName.of(right) + ") is not defined", this);
    }
}
