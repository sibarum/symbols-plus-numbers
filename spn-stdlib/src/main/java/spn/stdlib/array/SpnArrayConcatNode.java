package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.FieldType;
import spn.type.SpnArrayValue;

/**
 * Concatenates two arrays: concat([1, 2], [3, 4]) -> [1, 2, 3, 4]
 */
@SpnBuiltin(name = "concat", module = "Array", params = {"left", "right"}, returns = "Array")
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "concat")
public abstract class SpnArrayConcatNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue concat(SpnArrayValue left, SpnArrayValue right) {
        Object[] leftElems = left.getElements();
        Object[] rightElems = right.getElements();
        Object[] result = new Object[leftElems.length + rightElems.length];
        System.arraycopy(leftElems, 0, result, 0, leftElems.length);
        System.arraycopy(rightElems, 0, result, leftElems.length, rightElems.length);
        return new SpnArrayValue(FieldType.UNTYPED, result);
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("concat expects two arrays, got: "
                + left.getClass().getSimpleName() + " and "
                + right.getClass().getSimpleName(), this);
    }
}
