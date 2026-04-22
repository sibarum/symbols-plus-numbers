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
 * Converts a set to an array (insertion order).
 *
 * <pre>
 *   toArray({:red, :green, :blue}) -> [:red, :green, :blue]
 * </pre>
 */
@SpnBuiltin(name = "toArray", module = "Set", params = {"set"}, returns = "Array", receiver = "UntypedSet")
@NodeChild("set")
@NodeInfo(shortName = "toArray")
public abstract class SpnSetToArrayNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue toArray(SpnSetValue set) {
        return new SpnArrayValue(set.getElementType(), set.toArray());
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("toArray expects a Set, got: "
                + value.getClass().getSimpleName(), this);
    }
}
