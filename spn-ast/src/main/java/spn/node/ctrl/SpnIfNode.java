package spn.node.ctrl;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.SpnStatementNode;

/**
 * Conditional execution (if-then-else).
 *
 * KEY TRUFFLE CONCEPT: CountingConditionProfile
 *
 * Branch prediction is critical for CPU performance. The CountingConditionProfile
 * tracks how often the condition is true vs false. Graal uses this information to:
 *
 *   - Order branches so the hot path is fall-through (better CPU branch prediction)
 *   - Potentially eliminate the cold branch entirely if it's never taken
 *   - Make informed inlining decisions (only inline the hot path)
 *
 * Without profiling, Graal would treat both branches equally, producing worse code.
 * This is a simple one-line addition that gives Graal crucial runtime feedback.
 */
@NodeInfo(shortName = "if")
public final class SpnIfNode extends SpnStatementNode {

    @Child private SpnExpressionNode condition;
    @Child private SpnStatementNode thenBranch;
    @Child private SpnStatementNode elseBranch;

    private final CountingConditionProfile conditionProfile = CountingConditionProfile.create();

    public SpnIfNode(SpnExpressionNode condition, SpnStatementNode thenBranch,
                     SpnStatementNode elseBranch) {
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        if (conditionProfile.profile(evaluateCondition(frame))) {
            thenBranch.executeVoid(frame);
        } else if (elseBranch != null) {
            elseBranch.executeVoid(frame);
        }
    }

    private boolean evaluateCondition(VirtualFrame frame) {
        try {
            return condition.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw new SpnException("If condition must be a boolean, got: "
                    + e.getResult().getClass().getSimpleName(), this);
        }
    }
}
