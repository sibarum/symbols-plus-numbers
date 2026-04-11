package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "hypot", module = "Math", params = {"x", "y"}, returns = "Double")
@NodeChild("x")
@NodeChild("y")
@NodeInfo(shortName = "hypot")
public abstract class SpnHypotNode extends SpnExpressionNode {

    @Specialization
    protected double hypot(double x, double y) {
        return Math.hypot(x, y);
    }
}
