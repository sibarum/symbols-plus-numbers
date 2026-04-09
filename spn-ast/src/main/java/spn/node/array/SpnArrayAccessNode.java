package spn.node.array;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnArrayValue;

/**
 * Accesses an element of a SpnArrayValue by index.
 *
 * Index is 0-based. Out-of-bounds access throws SpnException.
 *
 * <pre>
 *   var access = SpnArrayAccessNodeGen.create(readArray, readIndex);
 *   // if array = [10, 20, 30] and index = 1, result = 20
 * </pre>
 */
@NodeChild("array")
@NodeChild("index")
@NodeInfo(shortName = "arrayAccess")
public abstract class SpnArrayAccessNode extends SpnExpressionNode {

    @Specialization
    protected Object access(SpnArrayValue array, long index) {
        int i = (int) index;
        if (i < 0 || i >= array.length()) {
            throw new SpnException(
                    "Array index out of bounds: " + index + " (length " + array.length() + ")",
                    this);
        }
        return array.get(i);
    }

    @Fallback
    protected Object typeError(Object array, Object index) {
        if (!(array instanceof SpnArrayValue)) {
            throw new SpnException("Expected an array, got: "
                    + SpnTypeName.of(array), this);
        }
        throw new SpnException("Array index must be a Long, got: "
                + SpnTypeName.of(index), this);
    }
}
