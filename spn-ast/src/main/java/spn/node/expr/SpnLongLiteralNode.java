package spn.node.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * A constant long value in the AST (e.g., the literal 42).
 *
 * Notice that we override BOTH executeLong and executeGeneric. This is important:
 * - executeLong returns the raw primitive, avoiding boxing entirely
 * - executeGeneric boxes it to Long (unavoidable when the parent uses the generic path)
 *
 * When a parent like SpnAddNode is specialized to addLongs(), it calls executeLong()
 * on its children. This literal returns the raw long -- zero allocation, zero boxing.
 * This is how Truffle achieves "as fast as C" for tight numeric loops.
 */
@NodeInfo(shortName = "long")
public final class SpnLongLiteralNode extends SpnExpressionNode {

    private final long value;

    public SpnLongLiteralNode(long value) {
        this.value = value;
    }

    @Override
    public long executeLong(VirtualFrame frame) {
        return value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}
