package spn.node.match;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnProductValue;
import spn.type.SpnStructValue;

/**
 * One arm of a match expression: a pattern, optional field bindings, optional guard,
 * and a body expression.
 *
 * Execution of tryMatch():
 *   1. Test the pattern against the subject value
 *   2. If the pattern matches, bind destructured fields into frame slots
 *   3. If a guard is present, evaluate it -- the branch only matches if the guard is true
 *   4. Return whether this branch matched
 *
 * Then SpnMatchNode calls execute() on the matching branch to get the result.
 *
 * Field bindings (the bindingSlots array):
 *   - For Struct patterns: one slot per field. bindingSlots[i] is the frame slot
 *     for field i. Use -1 to skip a field (unnamed in the pattern).
 *   - For Product patterns: same layout, one slot per component.
 *   - For Wildcard: bindingSlots[0] is the slot to bind the whole value. Empty = no binding.
 *   - For Literal: empty (no bindings).
 *
 * Example: "Circle(r) | r > 0 -> pi * r * r"
 * <pre>
 *   new SpnMatchBranchNode(
 *       new MatchPattern.Struct(circleDesc),
 *       new int[]{radiusSlot},       // bind field 0 → radiusSlot
 *       guardExpr,                   // r > 0
 *       bodyExpr                     // pi * r * r
 *   )
 * </pre>
 *
 * Truffle performance:
 * The pattern is @CompilationFinal, so Graal knows its exact type and constant-folds
 * the matches() call. The binding loop uses @ExplodeLoop with @CompilationFinal slots,
 * so each field write becomes a single frame.setObject() instruction.
 */
@NodeInfo(shortName = "matchBranch")
public final class SpnMatchBranchNode extends Node {

    @CompilationFinal
    private final MatchPattern pattern;

    @CompilationFinal(dimensions = 1)
    private final int[] bindingSlots;

    @Child private SpnExpressionNode guard;
    @Child private SpnExpressionNode body;

    public SpnMatchBranchNode(MatchPattern pattern, int[] bindingSlots,
                              SpnExpressionNode guard, SpnExpressionNode body) {
        this.pattern = pattern;
        this.bindingSlots = bindingSlots;
        this.guard = guard;
        this.body = body;
    }

    /** Convenience: no guard. */
    public SpnMatchBranchNode(MatchPattern pattern, int[] bindingSlots,
                              SpnExpressionNode body) {
        this(pattern, bindingSlots, null, body);
    }

    /**
     * Tests whether this branch matches the subject value. If the pattern matches,
     * binds destructured fields into the frame and evaluates the guard (if any).
     *
     * @return true if the pattern matches AND the guard (if present) is true
     */
    public boolean tryMatch(VirtualFrame frame, Object value) {
        if (!pattern.matches(value)) {
            return false;
        }
        bind(frame, value);
        if (guard != null) {
            return evaluateGuard(frame);
        }
        return true;
    }

    /**
     * Executes the body expression and returns its result.
     * Only called after tryMatch() returned true.
     */
    public Object execute(VirtualFrame frame) {
        return body.executeGeneric(frame);
    }

    public MatchPattern getPattern() {
        return pattern;
    }

    // ── Binding ─────────────────────────────────────────────────────────────

    @ExplodeLoop
    private void bind(VirtualFrame frame, Object value) {
        switch (pattern) {
            case MatchPattern.Struct _ -> bindIndexed(frame, ((SpnStructValue) value).getFields());
            case MatchPattern.Product _ -> bindIndexed(frame, ((SpnProductValue) value).getComponents());
            case MatchPattern.Wildcard _ -> {
                if (bindingSlots.length > 0 && bindingSlots[0] >= 0) {
                    frame.setObject(bindingSlots[0], value);
                }
            }
            case MatchPattern.Literal _ -> { /* no bindings for literals */ }
        }
    }

    @ExplodeLoop
    private void bindIndexed(VirtualFrame frame, Object[] values) {
        for (int i = 0; i < bindingSlots.length; i++) {
            if (bindingSlots[i] >= 0) {
                frame.setObject(bindingSlots[i], values[i]);
            }
        }
    }

    private boolean evaluateGuard(VirtualFrame frame) {
        try {
            return guard.executeBoolean(frame);
        } catch (UnexpectedResultException e) {
            throw new SpnException("Match guard must evaluate to a boolean, got: "
                    + e.getResult().getClass().getSimpleName(), this);
        }
    }
}
