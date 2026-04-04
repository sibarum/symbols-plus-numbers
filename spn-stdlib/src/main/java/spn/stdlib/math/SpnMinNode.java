package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Returns the smaller of two values: min(3, 7) -> 3
 */
@SpnBuiltin(name = "min", module = "Math", params = {"left", "right"})
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "min")
public abstract class SpnMinNode extends SpnExpressionNode {

    @Specialization
    protected long minLongs(long left, long right) {
        return Math.min(left, right);
    }

    @Specialization
    protected double minDoubles(double left, double right) {
        return Math.min(left, right);
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("min expects two numbers", this);
    }
}
