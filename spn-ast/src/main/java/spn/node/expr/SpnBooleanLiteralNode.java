package spn.node.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

@NodeInfo(shortName = "boolean")
public final class SpnBooleanLiteralNode extends SpnExpressionNode {

    private final boolean value;

    public SpnBooleanLiteralNode(boolean value) {
        this.value = value;
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) {
        return value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}
