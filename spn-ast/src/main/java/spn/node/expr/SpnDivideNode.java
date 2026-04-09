package spn.node.expr;

import spn.language.SpnTypeName;
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
        if (right == 0) {
            throw new SpnException("Division by zero", this);
        }
        return left / right;
    }

    @Specialization
    protected double divDoubles(double left, double right) {
        if (right == 0.0) {
            throw new SpnException("Division by zero", this);
        }
        return left / right;
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Cannot divide " + SpnTypeName.of(left)
                + " and " + SpnTypeName.of(right), this);
    }
}
