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
 * Returns a new dictionary with the given key set to value. Overwrites any
 * existing entry for that key.
 *
 * <pre>
 *   put({:a 1}, :b, 2) -> {:a 1, :b 2}
 *   put({:a 1}, :a, 9) -> {:a 9}
 * </pre>
 */
@SpnBuiltin(name = "put", module = "Dict", params = {"dict", "key", "value"}, returns = "Dict", receiver = "UntypedDict")
@NodeChild("dict")
@NodeChild("key")
@NodeChild("value")
@NodeInfo(shortName = "put")
public abstract class SpnDictPutNode extends SpnExpressionNode {

    @Specialization
    protected SpnDictionaryValue put(SpnDictionaryValue dict, SpnSymbol key, Object value) {
        return dict.set(key, value);
    }

    @Fallback
    protected Object typeError(Object dict, Object key, Object value) {
        if (!(dict instanceof SpnDictionaryValue)) {
            throw new SpnException("put expects a Dict as first argument, got: "
                    + SpnTypeName.of(dict), this);
        }
        throw new SpnException("put expects a Symbol key, got: "
                + SpnTypeName.of(key), this);
    }
}
