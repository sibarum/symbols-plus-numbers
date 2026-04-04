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
import spn.type.SpnArrayValue;

import java.util.ArrayList;

/**
 * Keeps elements that satisfy a predicate function.
 *
 * <pre>
 *   filter([1, 2, 3, 4], isEven) -> [2, 4]
 * </pre>
 */
@SpnBuiltin(name = "filter", module = "Array", params = {"array"}, returns = "Array")
@SpnParamHint(name = "predicate", function = true)
@NodeChild("array")
@NodeInfo(shortName = "filter")
public abstract class SpnArrayFilterNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnArrayFilterNode(CallTarget predicate) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(predicate);
    }

    @Specialization
    protected SpnArrayValue filter(SpnArrayValue array) {
        Object[] elements = array.getElements();
        var kept = new ArrayList<>();
        for (Object element : elements) {
            Object result = callNode.call(element);
            if (result instanceof Boolean b && b) {
                kept.add(element);
            }
        }
        return new SpnArrayValue(array.getElementType(), kept.toArray());
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("filter expects an array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
