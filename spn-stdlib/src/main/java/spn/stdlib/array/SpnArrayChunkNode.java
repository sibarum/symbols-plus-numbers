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
 * Splits an array into chunks of the given size.
 * chunk([1,2,3,4,5], 2) -> [[1,2], [3,4], [5]]
 */
@SpnBuiltin(name = "chunk", module = "Array", params = {"array", "size"}, returns = "Array", receiver = "UntypedArray")
@NodeChild("array")
@NodeChild("size")
@NodeInfo(shortName = "chunk")
public abstract class SpnArrayChunkNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue chunk(SpnArrayValue array, long size) {
        if (size <= 0) throw new SpnException("chunk: size must be positive", this);
        Object[] elems = array.getElements();
        int n = (int) size;
        int numChunks = (elems.length + n - 1) / n;
        Object[] chunks = new Object[numChunks];
        for (int i = 0; i < numChunks; i++) {
            int start = i * n;
            int end = Math.min(start + n, elems.length);
            Object[] c = new Object[end - start];
            System.arraycopy(elems, start, c, 0, end - start);
            chunks[i] = new SpnArrayValue(FieldType.UNTYPED, c);
        }
        return new SpnArrayValue(FieldType.UNTYPED, chunks);
    }

    @Fallback
    protected Object typeError(Object array, Object size) {
        throw new SpnException("chunk expects (array, int)", this);
    }
}
