package spn.node;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.type.SpnStructDescriptor;
import spn.type.SpnStructValue;

/**
 * Prefix unary operator: {@code inspect expr}
 *
 * Evaluates the argument expression, then:
 * <ol>
 *   <li>If the result is a {@link SpnStructValue} whose descriptor has a registered
 *       inspect function, calls it and returns the resulting string.</li>
 *   <li>Otherwise, falls back to {@code value.toString()}.</li>
 * </ol>
 *
 * The inspect function is registered on the struct descriptor when the parser
 * encounters {@code pure inspect(StructType) -> string = ...}.
 */
@NodeInfo(shortName = "inspect")
public final class SpnInspectNode extends SpnExpressionNode {

    @Child private SpnExpressionNode argument;

    public SpnInspectNode(SpnExpressionNode argument) {
        this.argument = argument;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = argument.executeGeneric(frame);

        if (value instanceof SpnStructValue sv) {
            SpnStructDescriptor desc = sv.getDescriptor();
            CallTarget target = desc.getInspectTarget();
            if (target != null) {
                return target.call(value);
            }
        }

        return value != null ? value.toString() : "null";
    }
}
