package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Clamps a value to [lo, hi]: clamp(15, 0, 10) -> 10
 */
@SpnBuiltin(name = "clamp", module = "Math", params = {"value", "lo", "hi"})
@NodeChild("value")
@NodeChild("lo")
@NodeChild("hi")
@NodeInfo(shortName = "clamp")
public abstract class SpnClampNode extends SpnExpressionNode {

    @Specialization
    protected long clampLongs(long value, long lo, long hi) {
        return Math.max(lo, Math.min(value, hi));
    }

    @Specialization
    protected double clampDoubles(double value, double lo, double hi) {
        return Math.max(lo, Math.min(value, hi));
    }

    @Fallback
    protected Object typeError(Object value, Object lo, Object hi) {
        throw new SpnException("clamp expects three numbers", this);
    }
}
