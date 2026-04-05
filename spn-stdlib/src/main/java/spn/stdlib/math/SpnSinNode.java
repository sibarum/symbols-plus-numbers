package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "sin", module = "Math", params = {"value"}, returns = "Double")
@NodeChild("value")
@NodeInfo(shortName = "sin")
public abstract class SpnSinNode extends SpnExpressionNode {

    @Specialization
    protected double doSin(double value) {
        return Math.sin(value);
    }
}
