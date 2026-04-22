package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;

import java.util.Arrays;

/**
 * Drops the first n elements of an array, returning the rest.
 *
 * <pre>
 *   drop([1, 2, 3, 4, 5], 2) -> [3, 4, 5]
 * </pre>
 */
@SpnBuiltin(name = "drop", module = "Array", params = {"array", "count"}, returns = "Array", receiver = "UntypedArray")
@NodeChild("array")
@NodeChild("count")
@NodeInfo(shortName = "drop")
public abstract class SpnArrayDropNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue drop(SpnArrayValue array, long count) {
        int n = (int) Math.min(count, array.length());
        if (n < 0) n = 0;
        return new SpnArrayValue(array.getElementType(),
                Arrays.copyOfRange(array.getElements(), n, array.length()));
    }

    @Fallback
    protected Object typeError(Object array, Object count) {
        throw new SpnException("drop expects (array, long)", this);
    }
}
