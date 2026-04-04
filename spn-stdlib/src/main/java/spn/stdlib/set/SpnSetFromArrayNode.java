package spn.stdlib.set;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;
import spn.type.SpnSetValue;

/**
 * Creates a set from an array (deduplicates).
 *
 * <pre>
 *   fromArray([1, 2, 2, 3]) -> {1, 2, 3}
 * </pre>
 */
@SpnBuiltin(name = "fromArray", module = "Set", params = {"array"}, returns = "Set")
@NodeChild("array")
@NodeInfo(shortName = "fromArray")
public abstract class SpnSetFromArrayNode extends SpnExpressionNode {

    @Specialization
    protected SpnSetValue fromArray(SpnArrayValue array) {
        return SpnSetValue.of(array.getElementType(), array.getElements());
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("fromArray expects an Array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
