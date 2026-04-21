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
 * Returns a new dictionary with the given key removed.
 *
 * <pre>
 *   remove({:a 1, :b 2}, :a) -> {:b 2}
 * </pre>
 */
@SpnBuiltin(name = "remove", module = "Dict", params = {"dict", "key"}, returns = "Dict", receiver = "Dict")
@NodeChild("dict")
@NodeChild("key")
@NodeInfo(shortName = "remove")
public abstract class SpnDictRemoveNode extends SpnExpressionNode {

    @Specialization
    protected SpnDictionaryValue remove(SpnDictionaryValue dict, SpnSymbol key) {
        return dict.remove(key);
    }

    @Fallback
    protected Object typeError(Object dict, Object key) {
        throw new SpnException("remove expects (Dict, Symbol)", this);
    }
}
