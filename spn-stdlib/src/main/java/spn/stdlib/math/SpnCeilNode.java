package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Ceiling: rounds up to nearest integer. ceil(3.2) -> 4
 */
@SpnBuiltin(name = "ceil", module = "Math", params = {"value"}, returns = "Long")
@NodeChild("value")
@NodeInfo(shortName = "ceil")
public abstract class SpnCeilNode extends SpnExpressionNode {

    @Specialization
    protected long ceilLong(long value) {
        return value;
    }

    @Specialization
    protected long ceilDouble(double value) {
        return (long) Math.ceil(value);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("ceil expects a number, got: "
                + value.getClass().getSimpleName(), this);
    }
}
