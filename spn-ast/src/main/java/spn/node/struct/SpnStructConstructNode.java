package spn.node.struct;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.type.SpnStructDescriptor;
import spn.type.SpnStructValue;

/**
 * Constructs an immutable SpnStructValue from field expression nodes.
 *
 * Each child evaluates to one field value, in field declaration order.
 *
 * Usage (what a parser would produce for "Circle(5.0)"):
 * <pre>
 *   var construct = new SpnStructConstructNode(circleDesc,
 *       new SpnDoubleLiteralNode(5.0));
 * </pre>
 *
 * @ExplodeLoop unrolls the field evaluation since the field count is
 * fixed at AST construction time.
 */
@NodeInfo(shortName = "structConstruct")
public final class SpnStructConstructNode extends SpnExpressionNode {

    @Children private final SpnExpressionNode[] fieldNodes;
    private final SpnStructDescriptor descriptor;

    public SpnStructConstructNode(SpnStructDescriptor descriptor,
                                  SpnExpressionNode... fieldNodes) {
        this.descriptor = descriptor;
        this.fieldNodes = fieldNodes;
    }

    @Override
    @ExplodeLoop
    public Object executeGeneric(VirtualFrame frame) {
        Object[] fields = new Object[fieldNodes.length];
        for (int i = 0; i < fieldNodes.length; i++) {
            fields[i] = fieldNodes[i].executeGeneric(frame);
        }
        return new SpnStructValue(descriptor, fields);
    }
}
