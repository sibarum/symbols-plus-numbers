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
 * Zips two arrays into an array of tuples, truncating to the shorter length.
 *
 * <pre>
 *   zip([1, 2, 3], ["a", "b"]) -> [(1, "a"), (2, "b")]
 * </pre>
 */
@SpnBuiltin(name = "zip", module = "Array", params = {"left", "right"}, returns = "Array")
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "zip")
public abstract class SpnArrayZipNode extends SpnExpressionNode {

    private static final SpnTupleDescriptor PAIR_DESC =
            new SpnTupleDescriptor(FieldType.UNTYPED, FieldType.UNTYPED);

    @Specialization
    protected SpnArrayValue zip(SpnArrayValue left, SpnArrayValue right) {
        int len = Math.min(left.length(), right.length());
        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = new SpnTupleValue(PAIR_DESC, left.get(i), right.get(i));
        }
        return new SpnArrayValue(FieldType.UNTYPED, result);
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("zip expects two arrays", this);
    }
}
