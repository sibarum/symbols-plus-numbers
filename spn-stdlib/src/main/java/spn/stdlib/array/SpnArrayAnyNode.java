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

/**
 * Returns true if any element satisfies the predicate.
 *
 * <pre>
 *   any([1, 2, 3], isEven) -> true
 * </pre>
 */
@SpnBuiltin(name = "any", module = "Array", params = {"array"}, returns = "Boolean", receiver = "Array")
@SpnParamHint(name = "predicate", function = true)
@NodeChild("array")
@NodeInfo(shortName = "any")
public abstract class SpnArrayAnyNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnArrayAnyNode(CallTarget predicate) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(predicate);
    }

    @Specialization
    protected boolean any(SpnArrayValue array) {
        for (Object element : array.getElements()) {
            Object result = callNode.call(element);
            if (result instanceof Boolean b && b) {
                return true;
            }
        }
        return false;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("any expects an array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
