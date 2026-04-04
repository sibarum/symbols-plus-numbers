package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Square root: sqrt(9) -> 3.0
 */
@SpnBuiltin(name = "sqrt", module = "Math", params = {"value"}, returns = "Double")
@NodeChild("value")
@NodeInfo(shortName = "sqrt")
public abstract class SpnSqrtNode extends SpnExpressionNode {

    @Specialization
    protected double sqrtLong(long value) {
        return Math.sqrt(value);
    }

    @Specialization
    protected double sqrtDouble(double value) {
        return Math.sqrt(value);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("sqrt expects a number, got: "
                + value.getClass().getSimpleName(), this);
    }
}
