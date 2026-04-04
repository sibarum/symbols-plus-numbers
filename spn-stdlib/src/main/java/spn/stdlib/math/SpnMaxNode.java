package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Returns the larger of two values: max(3, 7) -> 7
 */
@SpnBuiltin(name = "max", module = "Math", params = {"left", "right"})
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "max")
public abstract class SpnMaxNode extends SpnExpressionNode {

    @Specialization
    protected long maxLongs(long left, long right) {
        return Math.max(left, right);
    }

    @Specialization
    protected double maxDoubles(double left, double right) {
        return Math.max(left, right);
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("max expects two numbers", this);
    }
}
