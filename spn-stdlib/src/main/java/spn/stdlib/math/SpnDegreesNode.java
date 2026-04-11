package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "degrees", module = "Math", params = {"radians"}, returns = "Double")
@NodeChild("radians")
@NodeInfo(shortName = "degrees")
public abstract class SpnDegreesNode extends SpnExpressionNode {

    @Specialization
    protected double degrees(double radians) {
        return Math.toDegrees(radians);
    }
}
