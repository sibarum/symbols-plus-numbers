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
 * Appends a single element to an array: append([1, 2], 3) -> [1, 2, 3]
 * Returns a new array — the original is not modified.
 */
@SpnBuiltin(name = "append", module = "Array", params = {"array", "element"}, returns = "Array")
@NodeChild("array")
@NodeChild("element")
@NodeInfo(shortName = "append")
public abstract class SpnArrayAppendNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue append(SpnArrayValue array, Object element) {
        Object[] old = array.getElements();
        Object[] result = new Object[old.length + 1];
        System.arraycopy(old, 0, result, 0, old.length);
        result[old.length] = element;
        return new SpnArrayValue(FieldType.UNTYPED, result);
    }

    @Fallback
    protected Object typeError(Object array, Object element) {
        throw new SpnException("append expects an array and an element, got: "
                + array.getClass().getSimpleName(), this);
    }
}
