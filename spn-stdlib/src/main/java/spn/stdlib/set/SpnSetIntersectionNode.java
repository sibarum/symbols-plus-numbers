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
 * Returns elements present in both sets.
 *
 * <pre>
 *   intersection({1, 2, 3}, {2, 3, 4}) -> {2, 3}
 * </pre>
 */
@SpnBuiltin(name = "intersection", module = "Set", params = {"left", "right"}, returns = "Set", receiver = "Set")
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "intersection")
public abstract class SpnSetIntersectionNode extends SpnExpressionNode {

    @Specialization
    protected SpnSetValue intersection(SpnSetValue left, SpnSetValue right) {
        return left.intersection(right);
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("intersection expects two Sets", this);
    }
}
