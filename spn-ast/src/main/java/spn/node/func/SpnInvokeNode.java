package spn.node.func;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * Invokes a function by evaluating argument expressions and calling its CallTarget.
 *
 * KEY TRUFFLE CONCEPT: DirectCallNode
 *
 * DirectCallNode is Truffle's mechanism for calling a known, fixed function.
 * Unlike a plain callTarget.call(), DirectCallNode enables Graal to:
 *   - Inline the callee's compiled code into the caller
 *   - Specialize the call based on observed argument types
 *   - Eliminate the call overhead entirely in hot paths
 *
 * For a pure function like add(a, b) = a + b, after inlining, the compiled code
 * at the call site becomes just an ADD instruction -- no function call at all.
 *
 * Usage:
 * <pre>
 *   var invoke = new SpnInvokeNode(addFunction.getCallTarget(),
 *       new SpnLongLiteralNode(3),
 *       new SpnLongLiteralNode(4));
 *   // execute → 7
 * </pre>
 */
@NodeInfo(shortName = "invoke")
public final class SpnInvokeNode extends SpnExpressionNode {

    @Children private final SpnExpressionNode[] argNodes;
    @Child private DirectCallNode callNode;

    public SpnInvokeNode(CallTarget callTarget, SpnExpressionNode... argNodes) {
        this.argNodes = argNodes;
        this.callNode = Truffle.getRuntime().createDirectCallNode(callTarget);
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].executeGeneric(frame);
        }
        return callNode.call(args);
    }
}
