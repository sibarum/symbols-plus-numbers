package spn.stdlib.array;

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
import spn.type.FieldType;
import spn.type.SpnArrayValue;

/**
 * Transforms each element of an array by applying a function.
 *
 * <pre>
 *   map([1, 2, 3], double) -> [2, 4, 6]
 * </pre>
 */
@SpnBuiltin(name = "map", module = "Array", params = {"array"}, returns = "Array")
@SpnParamHint(name = "function", function = true)
@NodeChild("array")
@NodeInfo(shortName = "map")
public abstract class SpnArrayMapNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnArrayMapNode(CallTarget function) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(function);
    }

    @Specialization
    protected SpnArrayValue map(SpnArrayValue array) {
        Object[] elements = array.getElements();
        Object[] result = new Object[elements.length];
        for (int i = 0; i < elements.length; i++) {
            result[i] = callNode.call(elements[i]);
        }
        return new SpnArrayValue(FieldType.UNTYPED, result);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("map expects an array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
