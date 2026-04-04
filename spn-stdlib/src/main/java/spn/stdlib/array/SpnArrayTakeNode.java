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
 * Returns the first n elements of an array.
 *
 * <pre>
 *   take([1, 2, 3, 4, 5], 3) -> [1, 2, 3]
 * </pre>
 */
@SpnBuiltin(name = "take", module = "Array", params = {"array", "count"}, returns = "Array")
@NodeChild("array")
@NodeChild("count")
@NodeInfo(shortName = "take")
public abstract class SpnArrayTakeNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue take(SpnArrayValue array, long count) {
        int n = (int) Math.min(count, array.length());
        if (n < 0) n = 0;
        return new SpnArrayValue(array.getElementType(),
                Arrays.copyOf(array.getElements(), n));
    }

    @Fallback
    protected Object typeError(Object array, Object count) {
        throw new SpnException("take expects (array, long)", this);
    }
}
