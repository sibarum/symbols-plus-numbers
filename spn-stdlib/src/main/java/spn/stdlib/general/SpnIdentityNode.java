package spn.stdlib.general;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "identity", module = "General", params = {"value"})
@NodeChild("value")
@NodeInfo(shortName = "identity")
public abstract class SpnIdentityNode extends SpnExpressionNode {

    @Specialization
    protected Object identity(Object value) {
        return value;
    }
}
