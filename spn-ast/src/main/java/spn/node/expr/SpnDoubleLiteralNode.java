package spn.node.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

@NodeInfo(shortName = "double")
public final class SpnDoubleLiteralNode extends SpnExpressionNode {

    private final double value;

    public SpnDoubleLiteralNode(double value) {
        this.value = value;
    }

    @Override
    public double executeDouble(VirtualFrame frame) {
        return value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}
