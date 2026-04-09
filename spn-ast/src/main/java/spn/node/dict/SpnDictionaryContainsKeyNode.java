package spn.node.dict;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnDictionaryValue;
import spn.type.SpnSymbol;

/**
 * Tests whether a dictionary contains a given symbol key. Returns boolean.
 */
@NodeChild("dict")
@NodeChild("key")
@NodeInfo(shortName = "dictContainsKey")
public abstract class SpnDictionaryContainsKeyNode extends SpnExpressionNode {

    @Specialization
    protected boolean containsKey(SpnDictionaryValue dict, SpnSymbol key) {
        return dict.containsKey(key);
    }

    @Fallback
    protected Object typeError(Object dict, Object key) {
        throw new SpnException("Expected (dictionary, symbol), got ("
                + SpnTypeName.of(dict) + ", "
                + SpnTypeName.of(key) + ")", this);
    }
}
