package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Floor: rounds down to nearest integer. floor(3.7) -> 3
 */
@SpnBuiltin(name = "floor", module = "Math", params = {"value"}, returns = "Long")
@NodeChild("value")
@NodeInfo(shortName = "floor")
public abstract class SpnFloorNode extends SpnExpressionNode {

    @Specialization
    protected long floorLong(long value) {
        return value;
    }

    @Specialization
    protected long floorDouble(double value) {
        return (long) Math.floor(value);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("floor expects a number, got: "
                + value.getClass().getSimpleName(), this);
    }
}
