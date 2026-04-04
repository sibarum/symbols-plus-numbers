package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Absolute value: abs(-5) -> 5, abs(-3.14) -> 3.14
 */
@SpnBuiltin(name = "abs", module = "Math", params = {"value"})
@NodeChild("value")
@NodeInfo(shortName = "abs")
public abstract class SpnAbsNode extends SpnExpressionNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long absLong(long value) {
        if (value == Long.MIN_VALUE) {
            throw new ArithmeticException("overflow");
        }
        return Math.abs(value);
    }

    @Specialization(replaces = "absLong")
    protected double absDouble(double value) {
        return Math.abs(value);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("abs expects a number, got: "
                + value.getClass().getSimpleName(), this);
    }
}
