package spn.node.dict;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnDictionaryValue;
import spn.type.SpnSymbol;

/**
 * Looks up a value in a dictionary by symbol key.
 * Throws SpnException if the key is not present.
 */
@NodeChild("dict")
@NodeChild("key")
@NodeInfo(shortName = "dictGet")
public abstract class SpnDictionaryGetNode extends SpnExpressionNode {

    @Specialization
    protected Object get(SpnDictionaryValue dict, SpnSymbol key) {
        Object value = dict.get(key);
        if (value == null && !dict.containsKey(key)) {
            throw new SpnException("Key :" + key.name() + " not found in dictionary", this);
        }
        return value;
    }

    @Fallback
    protected Object typeError(Object dict, Object key) {
        if (!(dict instanceof SpnDictionaryValue)) {
            throw new SpnException("Expected a dictionary, got: "
                    + dict.getClass().getSimpleName(), this);
        }
        throw new SpnException("Dictionary key must be a Symbol, got: "
                + key.getClass().getSimpleName(), this);
    }
}
