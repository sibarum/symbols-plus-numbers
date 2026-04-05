package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "tan", module = "Math", params = {"value"}, returns = "Double")
@NodeChild("value")
@NodeInfo(shortName = "tan")
public abstract class SpnTanNode extends SpnExpressionNode {

    @Specialization
    protected double doTan(double value) {
        return Math.tan(value);
    }
}
