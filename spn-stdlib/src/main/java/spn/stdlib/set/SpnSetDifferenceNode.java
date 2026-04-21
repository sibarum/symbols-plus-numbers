package spn.stdlib.set;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnSetValue;

/**
 * Returns elements in the left set that are not in the right set.
 *
 * <pre>
 *   difference({1, 2, 3}, {2, 3, 4}) -> {1}
 * </pre>
 */
@SpnBuiltin(name = "difference", module = "Set", params = {"left", "right"}, returns = "Set", receiver = "Set")
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "difference")
public abstract class SpnSetDifferenceNode extends SpnExpressionNode {

    @Specialization
    protected SpnSetValue difference(SpnSetValue left, SpnSetValue right) {
        return left.difference(right);
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("difference expects two Sets", this);
    }
}
