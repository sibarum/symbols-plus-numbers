package spn.stdlib.option;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnStructValue;

/**
 * Extracts the value from Some. Throws on None.
 *
 * <pre>
 *   unwrap(Some(42)) -> 42
 *   unwrap(None)     -> error!
 * </pre>
 */
@SpnBuiltin(name = "unwrap", module = "Option", params = {"option"}, receiver = "Option")
@NodeChild("option")
@NodeInfo(shortName = "unwrap")
public abstract class SpnUnwrapNode extends SpnExpressionNode {

    @Specialization(guards = "isSome(option)")
    protected Object unwrapSome(SpnStructValue option) {
        return option.get(0);
    }

    @Specialization(guards = "isNone(option)")
    protected Object unwrapNone(SpnStructValue option) {
        throw new SpnException("Cannot unwrap None", this);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("unwrap expects an Option (Some/None), got: "
                + value.getClass().getSimpleName(), this);
    }

    protected static boolean isSome(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.SOME;
    }

    protected static boolean isNone(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.NONE;
    }
}
