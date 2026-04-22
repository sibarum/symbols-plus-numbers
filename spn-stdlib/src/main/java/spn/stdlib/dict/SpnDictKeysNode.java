package spn.stdlib.dict;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.FieldType;
import spn.type.SpnArrayValue;
import spn.type.SpnDictionaryValue;

/**
 * Returns all keys of a dictionary as an array of symbols.
 *
 * <pre>
 *   keys({:name "Alice", :age "30"}) -> [:name, :age]
 * </pre>
 */
@SpnBuiltin(name = "keys", module = "Dict", params = {"dict"}, returns = "Array", receiver = "UntypedDict")
@NodeChild("dict")
@NodeInfo(shortName = "keys")
public abstract class SpnDictKeysNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue keys(SpnDictionaryValue dict) {
        return new SpnArrayValue(FieldType.SYMBOL, (Object[]) dict.keys());
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("keys expects a Dict, got: "
                + value.getClass().getSimpleName(), this);
    }
}
