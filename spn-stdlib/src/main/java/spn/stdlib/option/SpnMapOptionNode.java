package spn.stdlib.option;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.node.builtin.SpnParamHint;
import spn.type.SpnStructValue;

/**
 * Applies a function to the value inside Some, passes None through unchanged.
 *
 * <pre>
 *   mapOption(Some(3), double) -> Some(6)
 *   mapOption(None, double)    -> None
 * </pre>
 */
@SpnBuiltin(name = "mapOption", module = "Option", params = {"option"}, returns = "Option")
@SpnParamHint(name = "function", function = true)
@NodeChild("option")
@NodeInfo(shortName = "mapOption")
public abstract class SpnMapOptionNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnMapOptionNode(CallTarget function) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(function);
    }

    @Specialization(guards = "isSome(option)")
    protected SpnStructValue mapSome(SpnStructValue option) {
        Object result = callNode.call(option.get(0));
        return SpnOptionDescriptors.some(result);
    }

    @Specialization(guards = "isNone(option)")
    protected SpnStructValue mapNone(SpnStructValue option) {
        return option;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("mapOption expects an Option (Some/None), got: "
                + value.getClass().getSimpleName(), this);
    }

    protected static boolean isSome(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.SOME;
    }

    protected static boolean isNone(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.NONE;
    }
}
