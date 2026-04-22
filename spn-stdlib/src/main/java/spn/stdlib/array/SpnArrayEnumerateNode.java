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
import spn.type.SpnTupleDescriptor;
import spn.type.SpnTupleValue;

/**
 * Pairs each element with its index: enumerate(["a", "b"]) -> [(0, "a"), (1, "b")]
 */
@SpnBuiltin(name = "enumerate", module = "Array", params = {"array"}, returns = "Array", receiver = "UntypedArray")
@NodeChild("array")
@NodeInfo(shortName = "enumerate")
public abstract class SpnArrayEnumerateNode extends SpnExpressionNode {

    private static final SpnTupleDescriptor INDEX_PAIR_DESC =
            new SpnTupleDescriptor(FieldType.LONG, FieldType.UNTYPED);

    @Specialization
    protected SpnArrayValue enumerate(SpnArrayValue array) {
        Object[] elements = array.getElements();
        Object[] result = new Object[elements.length];
        for (int i = 0; i < elements.length; i++) {
            result[i] = new SpnTupleValue(INDEX_PAIR_DESC, (long) i, elements[i]);
        }
        return new SpnArrayValue(FieldType.UNTYPED, result);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("enumerate expects an array, got: "
                + value.getClass().getSimpleName(), this);
    }
}
