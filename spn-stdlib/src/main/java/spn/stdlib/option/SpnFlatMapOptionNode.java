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
 * Applies a function that returns an Option to the value inside Some, flattening the result.
 * Passes None through unchanged.
 *
 * <pre>
 *   flatMap(Some(3), safeDivide) -> Some(result) or None
 *   flatMap(None, safeDivide)    -> None
 * </pre>
 */
@SpnBuiltin(name = "flatMap", module = "Option", params = {"option"})
@SpnParamHint(name = "function", function = true)
@NodeChild("option")
@NodeInfo(shortName = "flatMap")
public abstract class SpnFlatMapOptionNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnFlatMapOptionNode(CallTarget function) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(function);
    }

    @Specialization(guards = "isSome(option)")
    protected Object flatMapSome(SpnStructValue option) {
        return callNode.call(option.get(0));
    }

    @Specialization(guards = "isNone(option)")
    protected SpnStructValue flatMapNone(SpnStructValue option) {
        return option;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("flatMap expects an Option (Some/None), got: "
                + value.getClass().getSimpleName(), this);
    }

    protected static boolean isSome(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.SOME;
    }

    protected static boolean isNone(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.NONE;
    }
}
