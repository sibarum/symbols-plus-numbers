package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Explicit coercion to integer. Truncates floats toward zero.
 * toInt(3.7) -> 3, toInt(-2.9) -> -2, toInt(5) -> 5
 */
@SpnBuiltin(name = "toInt", module = "Math", params = {"value"}, returns = "Long")
@NodeChild("value")
@NodeInfo(shortName = "toInt")
public abstract class SpnToIntNode extends SpnExpressionNode {

    @Specialization
    protected long fromLong(long value) {
        return value;
    }

    @Specialization
    protected long fromDouble(double value) {
        return (long) value;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("toInt expects a number", this);
    }
}
