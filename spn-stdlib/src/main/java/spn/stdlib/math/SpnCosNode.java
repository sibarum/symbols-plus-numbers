package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "cos", module = "Math")
@NodeChild("value")
@NodeInfo(shortName = "cos")
public abstract class SpnCosNode extends SpnExpressionNode {

    @Specialization
    protected double doCos(double value) {
        return Math.cos(value);
    }
}
