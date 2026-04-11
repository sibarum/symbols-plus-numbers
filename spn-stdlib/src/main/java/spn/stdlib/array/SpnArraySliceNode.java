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
 * Returns a sub-array from start (inclusive) to end (exclusive).
 */
@SpnBuiltin(name = "slice", module = "Array", params = {"array", "start", "end"}, returns = "Array")
@NodeChild("array")
@NodeChild("start")
@NodeChild("end")
@NodeInfo(shortName = "slice")
public abstract class SpnArraySliceNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue slice(SpnArrayValue array, long start, long end) {
        Object[] elems = array.getElements();
        int s = Math.max(0, (int) start);
        int e = Math.min(elems.length, (int) end);
        if (s >= e) return new SpnArrayValue(FieldType.UNTYPED);
        Object[] result = new Object[e - s];
        System.arraycopy(elems, s, result, 0, e - s);
        return new SpnArrayValue(FieldType.UNTYPED, result);
    }

    @Fallback
    protected Object typeError(Object array, Object start, Object end) {
        throw new SpnException("slice expects (array, int, int)", this);
    }
}
