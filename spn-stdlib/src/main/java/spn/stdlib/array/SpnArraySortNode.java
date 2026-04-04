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

import java.util.Arrays;

/**
 * Sorts an array using a comparator function.
 *
 * The comparator receives (a, b) and returns a Long:
 *   negative if a < b, zero if equal, positive if a > b.
 *
 * <pre>
 *   sort([3, 1, 2], compare) -> [1, 2, 3]
 * </pre>
 */
@SpnBuiltin(name = "sort", module = "Array", params = {"array"}, returns = "Array")
@SpnParamHint(name = "comparator", function = true)
@NodeChild("array")
@NodeInfo(shortName = "sort")
public abstract class SpnArraySortNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnArraySortNode(CallTarget comparator) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(comparator);
    }

    @Specialization
    protected SpnArrayValue sort(SpnArrayValue array) {
        Object[] elements = array.getElements().clone();
        Arrays.sort(elements, (a, b) -> {
            Object result = callNode.call(a, b);
            if (result instanceof Long l) {
                return Long.signum(l);
            }
            throw new SpnException("sort comparator must return Long", this);
        });
        return new SpnArrayValue(array.getElementType(), elements);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("sort expects an array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
