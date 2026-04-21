package spn.stdlib.array;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;
import spn.type.SpnDictionaryValue;
import spn.type.SpnSetValue;

/**
 * Returns the length/size of a collection or string.
 * Polymorphic: works on Array, String, Set, Dict.
 */
@SpnBuiltin(name = "length", module = "Array", params = {"value"}, returns = "Long", receiver = "Array")
@NodeChild("value")
@NodeInfo(shortName = "length")
public abstract class SpnLengthNode extends SpnExpressionNode {

    @Specialization
    protected long lengthArray(SpnArrayValue array) {
        return array.length();
    }

    @Specialization
    protected long lengthString(String str) {
        return str.length();
    }

    @Specialization
    protected long lengthSet(SpnSetValue set) {
        return set.size();
    }

    @Specialization
    protected long lengthDict(SpnDictionaryValue dict) {
        return dict.size();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("length expects an array, string, set, or dict", this);
    }
}
