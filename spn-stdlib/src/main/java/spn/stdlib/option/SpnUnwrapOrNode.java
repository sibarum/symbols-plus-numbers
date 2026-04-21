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
 * Extracts the value from Some, or returns a default for None.
 *
 * <pre>
 *   unwrapOr(Some(42), 0) -> 42
 *   unwrapOr(None, 0)     -> 0
 * </pre>
 */
@SpnBuiltin(name = "unwrapOr", module = "Option", params = {"option", "defaultValue"}, receiver = "Option")
@NodeChild("option")
@NodeChild("defaultValue")
@NodeInfo(shortName = "unwrapOr")
public abstract class SpnUnwrapOrNode extends SpnExpressionNode {

    @Specialization(guards = "isSome(option)")
    protected Object unwrapSome(SpnStructValue option, Object defaultValue) {
        return option.get(0);
    }

    @Specialization(guards = "isNone(option)")
    protected Object unwrapNone(SpnStructValue option, Object defaultValue) {
        return defaultValue;
    }

    @Fallback
    protected Object typeError(Object value, Object defaultValue) {
        throw new SpnException("unwrapOr expects an Option (Some/None), got: "
                + value.getClass().getSimpleName(), this);
    }

    protected static boolean isSome(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.SOME;
    }

    protected static boolean isNone(SpnStructValue sv) {
        return sv.getDescriptor() == SpnOptionDescriptors.NONE;
    }
}
