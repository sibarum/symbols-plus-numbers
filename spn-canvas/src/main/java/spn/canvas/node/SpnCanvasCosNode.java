package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

@NodeChild("value")
@NodeInfo(shortName = "cos")
public abstract class SpnCanvasCosNode extends SpnExpressionNode {

    @Specialization
    protected double doCos(double value) {
        return Math.cos(value);
    }
}
