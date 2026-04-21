package spn.node.stateful;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnBoundClosure;
import spn.type.SpnStatefulInstance;

/**
 * Produces a {@link SpnBoundClosure} wrapping a shared {@link CallTarget}
 * with the currently-in-scope {@code this} reference.
 *
 * <p>The {@code target}'s body takes {@code this} as its first argument;
 * the closure the user holds just has to supply the remaining args.
 */
@NodeInfo(shortName = "doClosure")
public final class SpnDoClosureNode extends SpnExpressionNode {

    @Child private SpnExpressionNode thisExpr;
    private final CallTarget target;

    public SpnDoClosureNode(SpnExpressionNode thisExpr, CallTarget target) {
        this.thisExpr = thisExpr;
        this.target = target;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object ths = thisExpr.executeGeneric(frame);
        if (!(ths instanceof SpnStatefulInstance)) {
            throw new SpnException("do() closure requires a stateful instance as this, got "
                    + (ths == null ? "null" : ths.getClass().getSimpleName()), this);
        }
        return new SpnBoundClosure(target, ths);
    }
}
