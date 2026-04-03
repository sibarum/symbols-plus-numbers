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
 * Functional dictionary update: returns a new dictionary with the key set to value.
 * The original dictionary is unchanged (immutable).
 */
@NodeChild("dict")
@NodeChild("key")
@NodeChild("value")
@NodeInfo(shortName = "dictSet")
public abstract class SpnDictionarySetNode extends SpnExpressionNode {

    @Specialization
    protected SpnDictionaryValue set(SpnDictionaryValue dict, SpnSymbol key, Object value) {
        return dict.set(key, value);
    }

    @Fallback
    protected Object typeError(Object dict, Object key, Object value) {
        if (!(dict instanceof SpnDictionaryValue)) {
            throw new SpnException("Expected a dictionary, got: "
                    + dict.getClass().getSimpleName(), this);
        }
        throw new SpnException("Dictionary key must be a Symbol, got: "
                + key.getClass().getSimpleName(), this);
    }
}
