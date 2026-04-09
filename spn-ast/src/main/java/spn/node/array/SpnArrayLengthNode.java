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
 * Returns the length of a SpnArrayValue as a long.
 */
@NodeChild("array")
@NodeInfo(shortName = "arrayLength")
public abstract class SpnArrayLengthNode extends SpnExpressionNode {

    @Specialization
    protected long length(SpnArrayValue array) {
        return array.length();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("Expected an array, got: "
                + SpnTypeName.of(value), this);
    }
}
