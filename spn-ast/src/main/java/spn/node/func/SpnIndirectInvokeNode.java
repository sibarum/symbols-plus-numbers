package spn.node.func;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Invokes a function value (a {@link CallTarget} stored in a variable or
 * returned by an expression) with the given arguments.
 *
 * Unlike {@link SpnInvokeNode} which uses {@link com.oracle.truffle.api.nodes.DirectCallNode}
 * for compile-time-known targets, this node uses {@link IndirectCallNode} because
 * the target is only known at runtime.
 */
public final class SpnIndirectInvokeNode extends SpnExpressionNode {

    @Child private SpnExpressionNode functionExpr;
    @Children private final SpnExpressionNode[] argNodes;
    @Child private IndirectCallNode callNode = Truffle.getRuntime().createIndirectCallNode();

    public SpnIndirectInvokeNode(SpnExpressionNode functionExpr, SpnExpressionNode... argNodes) {
        this.functionExpr = functionExpr;
        this.argNodes = argNodes;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object fn = functionExpr.executeGeneric(frame);
        if (!(fn instanceof CallTarget target)) {
            throw new SpnException("Cannot call non-function value: "
                    + fn.getClass().getSimpleName(), this);
        }
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].executeGeneric(frame);
        }
        return callNode.call(target, args);
    }
}
