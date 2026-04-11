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
 * Creates an array of integers from start (inclusive) to end (exclusive).
 * range(0, 5) -> [0, 1, 2, 3, 4]
 */
@SpnBuiltin(name = "range", module = "Array", params = {"start", "end"}, returns = "Array")
@NodeChild("start")
@NodeChild("end")
@NodeInfo(shortName = "range")
public abstract class SpnArrayRangeNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue range(long start, long end) {
        int len = (int) Math.max(0, end - start);
        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = start + i;
        }
        return new SpnArrayValue(FieldType.LONG, result);
    }

    @Fallback
    protected Object typeError(Object start, Object end) {
        throw new SpnException("range expects two integers", this);
    }
}
