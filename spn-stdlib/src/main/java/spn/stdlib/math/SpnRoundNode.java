package spn.stdlib.math;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "round", module = "Math", params = {"value"}, returns = "Long")
@NodeChild("value")
@NodeInfo(shortName = "round")
public abstract class SpnRoundNode extends SpnExpressionNode {

    @Specialization
    protected long doRound(double value) {
        return Math.round(value);
    }
}
