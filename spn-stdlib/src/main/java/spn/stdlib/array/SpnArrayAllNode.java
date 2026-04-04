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
 * Returns true if all elements satisfy the predicate.
 *
 * <pre>
 *   all([2, 4, 6], isEven) -> true
 * </pre>
 */
@SpnBuiltin(name = "all", module = "Array", params = {"array"}, returns = "Boolean")
@SpnParamHint(name = "predicate", function = true)
@NodeChild("array")
@NodeInfo(shortName = "all")
public abstract class SpnArrayAllNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnArrayAllNode(CallTarget predicate) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(predicate);
    }

    @Specialization
    protected boolean all(SpnArrayValue array) {
        for (Object element : array.getElements()) {
            Object result = callNode.call(element);
            if (result instanceof Boolean b && !b) {
                return false;
            }
        }
        return true;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("all expects an array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
