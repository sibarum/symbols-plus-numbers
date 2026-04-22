package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;
import spn.type.SpnSetValue;

/**
 * Checks if a collection contains an element, or a string contains a substring.
 * Polymorphic: works on Array, String, Set.
 */
@SpnBuiltin(name = "contains", module = "Array", params = {"collection", "element"}, returns = "Boolean", receiver = "UntypedArray")
@NodeChild("collection")
@NodeChild("element")
@NodeInfo(shortName = "contains")
public abstract class SpnContainsNode extends SpnExpressionNode {

    @Specialization
    protected boolean containsArray(SpnArrayValue array, Object element) {
        for (Object e : array.getElements()) {
            if (e.equals(element)) return true;
        }
        return false;
    }

    @Specialization
    protected boolean containsString(String str, String sub) {
        return str.contains(sub);
    }

    @Specialization
    protected boolean containsSet(SpnSetValue set, Object element) {
        return set.contains(element);
    }

    @Fallback
    protected Object typeError(Object collection, Object element) {
        throw new SpnException("contains expects an array, string, or set", this);
    }
}
