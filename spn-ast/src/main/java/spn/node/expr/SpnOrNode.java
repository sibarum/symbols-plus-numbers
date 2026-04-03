package spn.node.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Short-circuit boolean OR. Evaluates the right operand only if the left is false.
 *
 * This node is not DSL-generated because short-circuit evaluation requires manual
 * control over when the right child is executed.
 */
@NodeInfo(shortName = "||")
public final class SpnOrNode extends SpnExpressionNode {

    @SuppressWarnings("FieldMayBeFinal")
    @Child private SpnExpressionNode left;
    @SuppressWarnings("FieldMayBeFinal")
    @Child private SpnExpressionNode right;

    public SpnOrNode(SpnExpressionNode left, SpnExpressionNode right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        try {
            if (left.executeBoolean(frame)) {
                return true;
            }
        } catch (UnexpectedResultException e) {
            throw new SpnException("Left operand of || must be boolean", this);
        }
        try {
            return right.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw new SpnException("Right operand of || must be boolean", this);
        }
    }
}
