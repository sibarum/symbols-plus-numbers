package spn.node.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

@NodeInfo(shortName = "string")
public final class SpnStringLiteralNode extends SpnExpressionNode {

    private final String value;

    public SpnStringLiteralNode(String value) {
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}
