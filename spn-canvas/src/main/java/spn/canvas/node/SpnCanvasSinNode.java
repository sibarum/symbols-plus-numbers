package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

@NodeChild("value")
@NodeInfo(shortName = "sin")
public abstract class SpnCanvasSinNode extends SpnExpressionNode {

    @Specialization
    protected double doSin(double value) {
        return Math.sin(value);
    }
}
