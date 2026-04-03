package spn.node.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Division operator. Integer division for longs, floating-point division for doubles.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "/")
public abstract class SpnDivideNode extends SpnExpressionNode {

    @Specialization
    protected long divLongs(long left, long right) {
        return left / right;
    }

    @Specialization
    protected double divDoubles(double left, double right) {
        return left / right;
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Cannot divide " + left.getClass().getSimpleName()
                + " and " + right.getClass().getSimpleName(), this);
    }
}
