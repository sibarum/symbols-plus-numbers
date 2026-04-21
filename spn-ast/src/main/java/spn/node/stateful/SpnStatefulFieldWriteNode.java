package spn.node.stateful;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnStatefulInstance;

/**
 * Writes a value to a field on a {@link SpnStatefulInstance}. Returns the
 * written value (statement value) so it can participate in expressions.
 *
 * <p>Throws via {@link SpnStatefulInstance#set(String, Object)} if the
 * instance has been killed.
 */
@NodeInfo(shortName = "statefulWrite")
public final class SpnStatefulFieldWriteNode extends SpnExpressionNode {

    @Child private SpnExpressionNode instanceExpr;
    @Child private SpnExpressionNode valueExpr;
    private final String fieldName;

    public SpnStatefulFieldWriteNode(SpnExpressionNode instanceExpr,
                                     String fieldName,
                                     SpnExpressionNode valueExpr) {
        this.instanceExpr = instanceExpr;
        this.valueExpr = valueExpr;
        this.fieldName = fieldName;
    }

    public String getFieldName() { return fieldName; }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object inst = instanceExpr.executeGeneric(frame);
        if (!(inst instanceof SpnStatefulInstance s)) {
            throw new SpnException("expected stateful instance, got "
                    + (inst == null ? "null" : inst.getClass().getSimpleName()), this);
        }
        Object value = valueExpr.executeGeneric(frame);
        s.set(fieldName, value);
        return value;
    }
}
