package spn.node;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Executes a sequence of statements.
 *
 * KEY TRUFFLE CONCEPT: @Children
 * The @Children annotation marks an array of child nodes. Truffle tracks all children
 * for adoption (parent pointer management) and compilation. When this node is compiled,
 * Graal sees the exact array contents and can inline each child's execution.
 *
 * KEY TRUFFLE CONCEPT: @ExplodeLoop
 * This annotation tells Graal to fully unroll the loop at compile time. This is safe
 * because @Children arrays are compilation-final -- their length never changes after
 * the AST is constructed. After unrolling, each statement.executeVoid() call becomes
 * a direct, inlineable call with no loop overhead.
 *
 * Without @ExplodeLoop, the loop would remain in the compiled code, preventing Graal
 * from inlining each individual statement. The performance difference is dramatic.
 */
@NodeInfo(shortName = "block")
public final class SpnBlockNode extends SpnStatementNode {

    @Children private final SpnStatementNode[] statements;

    public SpnBlockNode(SpnStatementNode[] statements) {
        this.statements = statements;
    }

    @Override
    @ExplodeLoop
    public void executeVoid(VirtualFrame frame) {
        for (SpnStatementNode statement : statements) {
            statement.executeVoid(frame);
        }
    }
}
