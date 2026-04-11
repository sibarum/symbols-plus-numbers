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
 * Filters Some(value) by predicate. Returns None if predicate is false.
 * filterOption(Some(3), isPositive) -> Some(3)
 * filterOption(Some(-1), isPositive) -> None
 */
@SpnBuiltin(name = "filterOption", module = "Option", params = {"option"})
@SpnParamHint(name = "predicate", function = true)
@NodeChild("option")
@NodeInfo(shortName = "filterOption")
public abstract class SpnOptionFilterNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnOptionFilterNode(CallTarget predicate) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(predicate);
    }

    @Specialization(guards = "isSome(option)")
    protected SpnStructValue filterSome(SpnStructValue option) {
        Object value = option.getFields()[0];
        Object result = callNode.call(value);
        if (result instanceof Boolean b && b) return option;
        return SpnOptionDescriptors.none();
    }

    @Specialization(guards = "isNone(option)")
    protected SpnStructValue filterNone(SpnStructValue option) {
        return option;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("filterOption expects an Option (Some/None)", this);
    }

    protected static boolean isSome(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.SOME;
    }

    protected static boolean isNone(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.NONE;
    }
}
