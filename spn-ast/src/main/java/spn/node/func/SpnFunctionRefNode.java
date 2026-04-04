package spn.node.func;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import spn.node.SpnExpressionNode;

/**
 * Returns a known function's {@link CallTarget} as a runtime value.
 * This enables pure functions to be assigned to variables, passed as
 * arguments, and stored in data structures.
 */
public final class SpnFunctionRefNode extends SpnExpressionNode {

    private final CallTarget callTarget;

    public SpnFunctionRefNode(CallTarget callTarget) {
        this.callTarget = callTarget;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return callTarget;
    }
}
