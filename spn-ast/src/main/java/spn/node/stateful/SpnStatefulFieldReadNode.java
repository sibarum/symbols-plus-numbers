package spn.node.stateful;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnStatefulInstance;

/**
 * Reads a field from a {@link SpnStatefulInstance}. Instance is produced by a
 * child expression (typically a local variable read of the current block's
 * {@code this} binding, or an argument read in a {@code do()} closure body).
 *
 * <p>Throws via {@link SpnStatefulInstance#get(String)} if the instance has
 * already been killed (block exited) or if the field is absent.
 */
@NodeInfo(shortName = "statefulRead")
public final class SpnStatefulFieldReadNode extends SpnExpressionNode {

    @Child private SpnExpressionNode instanceExpr;
    private final String fieldName;

    public SpnStatefulFieldReadNode(SpnExpressionNode instanceExpr, String fieldName) {
        this.instanceExpr = instanceExpr;
        this.fieldName = fieldName;
    }

    public String getFieldName() { return fieldName; }

    public SpnExpressionNode getInstanceExpr() { return instanceExpr; }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object inst = instanceExpr.executeGeneric(frame);
        if (!(inst instanceof SpnStatefulInstance s)) {
            throw new SpnException("expected stateful instance, got "
                    + (inst == null ? "null" : inst.getClass().getSimpleName()), this);
        }
        return s.get(fieldName);
    }
}
