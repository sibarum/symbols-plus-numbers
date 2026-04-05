package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "atan", module = "Math", params = {"value"}, returns = "Double")
@NodeChild("value")
@NodeInfo(shortName = "atan")
public abstract class SpnAtanNode extends SpnExpressionNode {

    @Specialization
    protected double doAtan(double value) {
        return Math.atan(value);
    }
}
