package spn.node.match;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * A pattern matching expression -- the SPN equivalent of Haskell's case/of.
 *
 * Evaluates a subject expression, then tests each branch in order. The first
 * branch whose pattern matches (and whose guard, if any, is true) has its body
 * evaluated, and the result is returned.
 *
 * If no branch matches, throws a non-exhaustive match error.
 *
 * Example AST for:
 * <pre>
 *   match shape {
 *     Circle(r)          -> 3.14159 * r * r
 *     Rectangle(w, h)    -> w * h
 *     _                  -> 0
 *   }
 * </pre>
 *
 * <pre>
 *   SpnMatchNode(
 *     subject = readShape,
 *     branches = [
 *       SpnMatchBranchNode(Struct(circleDesc),    [rSlot],      circleBody),
 *       SpnMatchBranchNode(Struct(rectangleDesc), [wSlot,hSlot], rectBody),
 *       SpnMatchBranchNode(Wildcard(),            [],            zeroLiteral)
 *     ]
 *   )
 * </pre>
 *
 * KEY TRUFFLE CONCEPT: @ExplodeLoop over branches
 *
 * The branches array is @Children (compilation-final). @ExplodeLoop unrolls the
 * matching loop so Graal sees each branch as a separate if-else. Since each
 * branch's pattern is also compilation-final, the pattern.matches() call is
 * devirtualized and inlined. The compiled code becomes a chain of type checks
 * with direct field reads -- no loop, no virtual dispatch.
 *
 * For the Shape example with 3 branches, the compiled code is roughly:
 * <pre>
 *   Object value = subject();
 *   if (value instanceof SpnStructValue sv && sv.descriptor == CIRCLE) {
 *       frame[rSlot] = sv.fields[0];
 *       return 3.14159 * frame[rSlot] * frame[rSlot];
 *   }
 *   if (value instanceof SpnStructValue sv && sv.descriptor == RECTANGLE) {
 *       frame[wSlot] = sv.fields[0];
 *       frame[hSlot] = sv.fields[1];
 *       return frame[wSlot] * frame[hSlot];
 *   }
 *   return 0;
 * </pre>
 */
@NodeInfo(shortName = "match")
public final class SpnMatchNode extends SpnExpressionNode {

    @Child private SpnExpressionNode subject;
    @Children private final SpnMatchBranchNode[] branches;

    public SpnMatchNode(SpnExpressionNode subject, SpnMatchBranchNode... branches) {
        this.subject = subject;
        this.branches = branches;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object value = subject.executeGeneric(frame);

        for (SpnMatchBranchNode branch : branches) {
            if (branch.tryMatch(frame, value)) {
                return branch.execute(frame);
            }
        }

        throw new SpnException("Non-exhaustive match: no pattern matched value " + value, this);
    }
}
