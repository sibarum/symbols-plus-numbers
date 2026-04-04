package spn.node.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Modulo operator. Integer modulo for longs, floating-point modulo for doubles.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "%")
public abstract class SpnModuloNode extends SpnExpressionNode {

    @Specialization
    protected long modLongs(long left, long right) {
        if (right == 0) {
            throw new SpnException("Modulo by zero", this);
        }
        return left % right;
    }

    @Specialization
    protected double modDoubles(double left, double right) {
        if (right == 0.0) {
            throw new SpnException("Modulo by zero", this);
        }
        return left % right;
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Cannot modulo " + left.getClass().getSimpleName()
                + " and " + right.getClass().getSimpleName(), this);
    }
}
