package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;

/**
 * Returns a new array with elements in reverse order.
 */
@SpnBuiltin(name = "reverse", module = "Array", params = {"array"}, returns = "Array")
@NodeChild("array")
@NodeInfo(shortName = "reverse")
public abstract class SpnArrayReverseNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue reverse(SpnArrayValue array) {
        Object[] elements = array.getElements();
        Object[] result = new Object[elements.length];
        for (int i = 0; i < elements.length; i++) {
            result[i] = elements[elements.length - 1 - i];
        }
        return new SpnArrayValue(array.getElementType(), result);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("reverse expects an array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
