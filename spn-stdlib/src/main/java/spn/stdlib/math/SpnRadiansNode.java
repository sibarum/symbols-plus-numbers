package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "radians", module = "Math", params = {"degrees"}, returns = "Double")
@NodeChild("degrees")
@NodeInfo(shortName = "radians")
public abstract class SpnRadiansNode extends SpnExpressionNode {

    @Specialization
    protected double radians(double degrees) {
        return Math.toRadians(degrees);
    }
}
