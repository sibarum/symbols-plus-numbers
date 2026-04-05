package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

@NodeChild("value")
@NodeInfo(shortName = "round")
public abstract class SpnCanvasRoundNode extends SpnExpressionNode {

    @Specialization
    protected long roundDouble(double value) {
        return Math.round(value);
    }
}
