package spn.node.match;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnArrayValue;
import spn.type.SpnDictionaryValue;
import spn.type.SpnSymbol;
import spn.type.SpnProductValue;
import spn.type.SpnStructValue;
import spn.type.SpnTupleValue;

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

    public SpnExpressionNode getBody() {
        return body;
    }

    // ── Binding ─────────────────────────────────────────────────────────────

    @ExplodeLoop
    private void bind(VirtualFrame frame, Object value) {
        switch (pattern) {
            case MatchPattern.Struct _ -> bindIndexed(frame, ((SpnStructValue) value).getFields());
            case MatchPattern.Product _ -> bindIndexed(frame, ((SpnProductValue) value).getComponents());
            case MatchPattern.Tuple _ -> bindIndexed(frame, ((SpnTupleValue) value).getElements());
            case MatchPattern.EmptyArray _ -> { /* no bindings for empty array */ }
            case MatchPattern.EmptySet _ -> { /* no bindings for empty set */ }
            case MatchPattern.EmptyDictionary _ -> { /* no bindings for empty dict */ }
            case MatchPattern.DictionaryKeys dk -> {
                // Bind the value of each required key to its corresponding slot
                SpnDictionaryValue dv = (SpnDictionaryValue) value;
                SpnSymbol[] reqKeys = dk.requiredKeys();
                for (int i = 0; i < bindingSlots.length && i < reqKeys.length; i++) {
                    if (bindingSlots[i] >= 0) {
                        frame.setObject(bindingSlots[i], dv.get(reqKeys[i]));
                    }
                }
            }
            case MatchPattern.SetContaining _ -> {
                // Slot 0 gets the entire set
                if (bindingSlots.length > 0 && bindingSlots[0] >= 0) {
                    frame.setObject(bindingSlots[0], value);
                }
            }
            case MatchPattern.ArrayHeadTail _ -> {
                // Slot 0 = head, slot 1 = tail
                SpnArrayValue arr = (SpnArrayValue) value;
                if (bindingSlots.length > 0 && bindingSlots[0] >= 0) {
                    frame.setObject(bindingSlots[0], arr.head());
                }
                if (bindingSlots.length > 1 && bindingSlots[1] >= 0) {
                    frame.setObject(bindingSlots[1], arr.tail());
                }
            }
            case MatchPattern.ArrayExactLength _ -> bindIndexed(frame, ((SpnArrayValue) value).getElements());
            case MatchPattern.TupleElements te -> {
                Object[] elems = MatchPattern.TupleElements.extractFields(value);
                if (elems == null) break;
                for (int i = 0; i < te.arity(); i++) {
                    bindNested(frame, te.elements()[i], elems[i]);
                }
            }
            case MatchPattern.StructDestructure sd -> {
                SpnStructValue sv = (SpnStructValue) value;
                Object[] fields = sv.getFields();
                for (int i = 0; i < sd.fieldPatterns().length; i++) {
                    bindNested(frame, sd.fieldPatterns()[i], fields[i]);
                }
            }
            case MatchPattern.Capture c -> {
                frame.setObject(c.slot(), value);
            }
            case MatchPattern.StringPrefix sp -> {
                // Slot 0 gets the remainder after the prefix
                if (bindingSlots.length > 0 && bindingSlots[0] >= 0) {
                    frame.setObject(bindingSlots[0], sp.remainder((String) value));
                }
            }
            case MatchPattern.StringSuffix ss -> {
                // Slot 0 gets the prefix before the suffix
                if (bindingSlots.length > 0 && bindingSlots[0] >= 0) {
                    frame.setObject(bindingSlots[0], ss.prefix((String) value));
                }
            }
            case MatchPattern.StringRegex sr -> {
                // Slot 0 gets the full match, slots 1..N get capture groups
                String[] groups = sr.groups((String) value);
                for (int i = 0; i < bindingSlots.length && i < groups.length; i++) {
                    if (bindingSlots[i] >= 0) {
                        frame.setObject(bindingSlots[i], groups[i]);
                    }
                }
            }
            case MatchPattern.OfType _ -> {
                if (bindingSlots.length > 0 && bindingSlots[0] >= 0) {
                    frame.setObject(bindingSlots[0], value);
                }
            }
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

    /**
     * Recursively bind variables from nested composite patterns.
     * Capture nodes bind directly to their embedded frame slot.
     * Container nodes (TupleElements, StructDestructure) recurse into children.
     * All other patterns (Wildcard, Literal, Struct, etc.) produce no bindings.
     */
    private void bindNested(VirtualFrame frame, MatchPattern pat, Object value) {
        switch (pat) {
            case MatchPattern.Capture c -> frame.setObject(c.slot(), value);
            case MatchPattern.TupleElements te -> {
                Object[] elems = MatchPattern.TupleElements.extractFields(value);
                if (elems == null) break;
                for (int i = 0; i < te.arity(); i++) {
                    bindNested(frame, te.elements()[i], elems[i]);
                }
            }
            case MatchPattern.StructDestructure sd -> {
                Object[] fields = ((SpnStructValue) value).getFields();
                for (int i = 0; i < sd.fieldPatterns().length; i++) {
                    bindNested(frame, sd.fieldPatterns()[i], fields[i]);
                }
            }
            default -> { /* Wildcard, Literal, Struct, etc. — no binding */ }
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
