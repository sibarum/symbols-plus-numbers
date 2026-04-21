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
 * Tests if the left set is a subset of the right set.
 *
 * <pre>
 *   isSubset({1, 2}, {1, 2, 3}) -> true
 * </pre>
 */
@SpnBuiltin(name = "isSubset", module = "Set", params = {"left", "right"}, returns = "Boolean", receiver = "Set")
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "isSubset")
public abstract class SpnSetIsSubsetNode extends SpnExpressionNode {

    @Specialization
    protected boolean isSubset(SpnSetValue left, SpnSetValue right) {
        return left.isSubsetOf(right);
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("isSubset expects two Sets", this);
    }
}
