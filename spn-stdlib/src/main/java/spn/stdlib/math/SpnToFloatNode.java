package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Explicit coercion to float.
 * toFloat(3) -> 3.0, toFloat(3.14) -> 3.14
 */
@SpnBuiltin(name = "toFloat", module = "Math", params = {"value"}, returns = "Double")
@NodeChild("value")
@NodeInfo(shortName = "toFloat")
public abstract class SpnToFloatNode extends SpnExpressionNode {

    @Specialization
    protected double fromLong(long value) {
        return (double) value;
    }

    @Specialization
    protected double fromDouble(double value) {
        return value;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("toFloat expects a number", this);
    }
}
