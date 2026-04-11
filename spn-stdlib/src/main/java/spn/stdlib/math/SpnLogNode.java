package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "log", module = "Math", params = {"value"}, returns = "Double")
@NodeChild("value")
@NodeInfo(shortName = "log")
public abstract class SpnLogNode extends SpnExpressionNode {

    @Specialization
    protected double log(double value) {
        return Math.log(value);
    }
}
