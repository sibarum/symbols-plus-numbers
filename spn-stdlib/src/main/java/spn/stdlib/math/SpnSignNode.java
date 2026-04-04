package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Returns the sign of a number: -1, 0, or 1.
 */
@SpnBuiltin(name = "sign", module = "Math", params = {"value"}, returns = "Long")
@NodeChild("value")
@NodeInfo(shortName = "sign")
public abstract class SpnSignNode extends SpnExpressionNode {

    @Specialization
    protected long signLong(long value) {
        return Long.signum(value);
    }

    @Specialization
    protected long signDouble(double value) {
        if (value < 0) return -1L;
        if (value > 0) return 1L;
        return 0L;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("sign expects a number, got: "
                + value.getClass().getSimpleName(), this);
    }
}
