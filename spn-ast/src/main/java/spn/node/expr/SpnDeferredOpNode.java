package spn.node.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * Placeholder node for a binary operator whose dispatch can't be resolved
 * at parse time because it sits inside a macro body and the relevant
 * overload may be registered later in the same compilation unit. The
 * parser collects all deferred ops and re-runs dispatch at end-of-parse,
 * after every top-level declaration (including other macro expansions)
 * has had a chance to register operator overloads.
 *
 * <p>Operands are held as plain references (not {@code @Child}) so they
 * stay unadopted until the resolver builds the final invoke that adopts
 * them as its own children. The wrapper's {@link #delegate} child is
 * populated by {@link #setResolved} during the end-of-parse fixup; if
 * resolution fails for any deferred node, the parser raises a
 * {@link spn.lang.SpnParseException SpnParseException} with the macro
 * frame attached, so this node never reaches runtime in that case.
 */
@NodeInfo(shortName = "deferredOp")
public final class SpnDeferredOpNode extends SpnExpressionNode {

    public final String op;
    public final SpnExpressionNode leftRef;
    public final SpnExpressionNode rightRef;

    @Child private SpnExpressionNode delegate;

    public SpnDeferredOpNode(String op, SpnExpressionNode left, SpnExpressionNode right) {
        this.op = op;
        this.leftRef = left;
        this.rightRef = right;
    }

    public boolean isResolved() { return delegate != null; }

    public void setResolved(SpnExpressionNode resolved) {
        if (delegate != null) {
            throw new IllegalStateException("Deferred op already resolved");
        }
        this.delegate = insert(resolved);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        if (delegate == null) {
            throw new IllegalStateException(
                    "Unresolved deferred operator '" + op + "' reached runtime");
        }
        return delegate.executeGeneric(frame);
    }
}
