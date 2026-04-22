package spn.stdlib.dict;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnDictionaryValue;
import spn.type.SpnSymbol;

/**
 * Tests if a dictionary contains a key: hasKey({:a 1}, :a) -> true
 */
@SpnBuiltin(name = "hasKey", module = "Dict", params = {"dict", "key"}, returns = "Boolean", receiver = "UntypedDict")
@NodeChild("dict")
@NodeChild("key")
@NodeInfo(shortName = "hasKey")
public abstract class SpnDictHasKeyNode extends SpnExpressionNode {

    @Specialization
    protected boolean hasKey(SpnDictionaryValue dict, SpnSymbol key) {
        return dict.containsKey(key);
    }

    @Fallback
    protected Object typeError(Object dict, Object key) {
        throw new SpnException("hasKey expects (Dict, Symbol)", this);
    }
}
