package spn.stdlib.dict;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.language.SpnTypeName;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnDictionaryValue;
import spn.type.SpnSymbol;

/**
 * Looks up a value in a dictionary by symbol key. Throws if the key is missing.
 *
 * <pre>
 *   dictGet({:a 1, :b 2}, :a) -> 1
 * </pre>
 */
@SpnBuiltin(name = "dictGet", module = "Dict", params = {"dict", "key"}, receiver = "Dict", method = "get")
@NodeChild("dict")
@NodeChild("key")
@NodeInfo(shortName = "dictGet")
public abstract class SpnDictGetNode extends SpnExpressionNode {

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
            throw new SpnException("dictGet expects a Dict as first argument, got: "
                    + SpnTypeName.of(dict), this);
        }
        throw new SpnException("dictGet expects a Symbol key, got: "
                + SpnTypeName.of(key), this);
    }
}
