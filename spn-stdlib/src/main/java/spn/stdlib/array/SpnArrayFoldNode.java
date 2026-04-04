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
 * Reduces an array to a single value by applying a binary function.
 *
 * Direction-agnostic: iterates naturally from first to last element.
 * The function receives (accumulator, element) and returns the new accumulator.
 *
 * <pre>
 *   fold([1, 2, 3, 4], 0, add) -> 10
 *   fold(["a", "b", "c"], "", concat) -> "abc"
 * </pre>
 */
@SpnBuiltin(name = "fold", module = "Array", params = {"array", "init"})
@SpnParamHint(name = "function", function = true)
@NodeChild("array")
@NodeChild("init")
@NodeInfo(shortName = "fold")
public abstract class SpnArrayFoldNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnArrayFoldNode(CallTarget function) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(function);
    }

    @Specialization
    protected Object fold(SpnArrayValue array, Object init) {
        Object accumulator = init;
        Object[] elements = array.getElements();
        for (Object element : elements) {
            accumulator = callNode.call(accumulator, element);
        }
        return accumulator;
    }

    @Fallback
    protected Object typeError(Object array, Object init) {
        throw new SpnException("fold expects an array as first argument, got: "
                + array.getClass().getSimpleName(), this);
    }
}
