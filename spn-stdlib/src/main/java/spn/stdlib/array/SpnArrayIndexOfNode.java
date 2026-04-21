package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;

/**
 * Returns the index of the first occurrence of an element, or -1 if not found.
 */
@SpnBuiltin(name = "indexOf", module = "Array", params = {"collection", "element"}, returns = "Long", receiver = "Array")
@NodeChild("collection")
@NodeChild("element")
@NodeInfo(shortName = "indexOf")
public abstract class SpnArrayIndexOfNode extends SpnExpressionNode {

    @Specialization
    protected long indexOfArray(SpnArrayValue array, Object element) {
        Object[] elems = array.getElements();
        for (int i = 0; i < elems.length; i++) {
            if (elems[i].equals(element)) return i;
        }
        return -1L;
    }

    @Specialization
    protected long indexOfString(String str, String sub) {
        return str.indexOf(sub);
    }

    @Fallback
    protected Object typeError(Object collection, Object element) {
        throw new SpnException("indexOf expects an array or string", this);
    }
}
