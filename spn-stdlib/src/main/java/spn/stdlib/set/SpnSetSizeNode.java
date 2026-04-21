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
 * Returns the number of elements in a set.
 */
@SpnBuiltin(name = "size", module = "Set", params = {"set"}, returns = "Long", receiver = "Set")
@NodeChild("set")
@NodeInfo(shortName = "setSize")
public abstract class SpnSetSizeNode extends SpnExpressionNode {

    @Specialization
    protected long size(SpnSetValue set) {
        return set.size();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("size expects a Set, got: "
                + value.getClass().getSimpleName(), this);
    }
}
