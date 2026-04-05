package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "asin", module = "Math")
@NodeChild("value")
@NodeInfo(shortName = "asin")
public abstract class SpnAsinNode extends SpnExpressionNode {

    @Specialization
    protected double doAsin(double value) {
        return Math.asin(value);
    }
}
