package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "acos", module = "Math", params = {"value"}, returns = "Double")
@NodeChild("value")
@NodeInfo(shortName = "acos")
public abstract class SpnAcosNode extends SpnExpressionNode {

    @Specialization
    protected double doAcos(double value) {
        return Math.acos(value);
    }
}
