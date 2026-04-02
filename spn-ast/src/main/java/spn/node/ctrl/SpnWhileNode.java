package spn.node.ctrl;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.SpnStatementNode;

/**
 * While loop -- demonstrates Truffle's LoopNode and On-Stack Replacement (OSR).
 *
 * KEY TRUFFLE CONCEPT: LoopNode and OSR
 *
 * Naive loop implementation (a while loop in the execute method) works but misses
 * a critical optimization: On-Stack Replacement (OSR). OSR allows Graal to compile
 * a loop's body WHILE the loop is still running. Without OSR, a long-running loop
 * stays in the interpreter until the entire RootNode is compiled.
 *
 * LoopNode is Truffle's built-in loop infrastructure that enables OSR:
 *   1. Wrap the loop body in a RepeatingNode
 *   2. Create a LoopNode via Truffle.getRuntime().createLoopNode(repeatingNode)
 *   3. The LoopNode counts iterations and triggers OSR compilation automatically
 *
 * The RepeatingNode.executeRepeating() method returns true to continue looping,
 * false to stop. Truffle handles the rest: iteration counting, compilation triggers,
 * and the actual on-stack replacement mechanics.
 */
@NodeInfo(shortName = "while")
public final class SpnWhileNode extends SpnStatementNode {

    @Child private LoopNode loopNode;

    public SpnWhileNode(SpnExpressionNode condition, SpnStatementNode body) {
        this.loopNode = Truffle.getRuntime().createLoopNode(
                new SpnWhileRepeatingNode(condition, body));
    }

    @Override
    public void executeVoid(VirtualFrame frame) {
        loopNode.execute(frame);
    }

    /**
     * Inner RepeatingNode that contains the actual loop logic.
     * Each call to executeRepeating is one iteration.
     */
    private static final class SpnWhileRepeatingNode extends Node implements RepeatingNode {

        @Child private SpnExpressionNode condition;
        @Child private SpnStatementNode body;

        SpnWhileRepeatingNode(SpnExpressionNode condition, SpnStatementNode body) {
            this.condition = condition;
            this.body = body;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            if (!evaluateCondition(frame)) {
                return false;  // stop the loop
            }
            body.executeVoid(frame);
            return true;  // continue the loop
        }

        private boolean evaluateCondition(VirtualFrame frame) {
            try {
                return condition.executeBoolean(frame);
            } catch (UnexpectedResultException e) {
                throw new SpnException("While condition must be a boolean, got: "
                        + e.getResult().getClass().getSimpleName(), condition);
            }
        }
    }
}
