package spn.node.func;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * Invokes an impl method on a receiver value.
 *
 * The receiver expression is evaluated first and passed as argument 0 ("this")
 * to the method's CallTarget. The remaining arguments follow.
 *
 * For a call like {@code p.distanceTo(q)}, the receiver is {@code p},
 * argNodes is {@code [q]}, and the CallTarget receives {@code [p, q]}.
 */
@NodeInfo(shortName = "methodInvoke")
public final class SpnMethodInvokeNode extends SpnExpressionNode {

    @Child private SpnExpressionNode receiverNode;
    @Children private final SpnExpressionNode[] argNodes;
    @Child private DirectCallNode callNode;

    public SpnMethodInvokeNode(CallTarget callTarget, SpnExpressionNode receiver,
                               SpnExpressionNode... argNodes) {
        this.receiverNode = receiver;
        this.argNodes = argNodes;
        this.callNode = Truffle.getRuntime().createDirectCallNode(callTarget);
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object[] args = new Object[argNodes.length + 1];
        args[0] = receiverNode.executeGeneric(frame);
        for (int i = 0; i < argNodes.length; i++) {
            args[i + 1] = argNodes[i].executeGeneric(frame);
        }
        return callNode.call(args);
    }
}
