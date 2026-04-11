package spn.stdlib.set;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnSetValue;

@SpnBuiltin(name = "setRemove", module = "Set", params = {"set", "element"}, returns = "Set")
@NodeChild("set")
@NodeChild("element")
@NodeInfo(shortName = "setRemove")
public abstract class SpnSetRemoveNode extends SpnExpressionNode {

    @Specialization
    protected SpnSetValue remove(SpnSetValue set, Object element) {
        return set.remove(element);
    }

    @Fallback
    protected Object typeError(Object set, Object element) {
        throw new SpnException("setRemove expects a set", this);
    }
}
