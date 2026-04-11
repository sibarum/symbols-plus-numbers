package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "atan2", module = "Math", params = {"y", "x"}, returns = "Double")
@NodeChild("y")
@NodeChild("x")
@NodeInfo(shortName = "atan2")
public abstract class SpnAtan2Node extends SpnExpressionNode {

    @Specialization
    protected double atan2(double y, double x) {
        return Math.atan2(y, x);
    }
}
