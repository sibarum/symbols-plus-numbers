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
 * Returns all values of a dictionary as an array.
 *
 * <pre>
 *   values({:name "Alice", :age "30"}) -> ["Alice", "30"]
 * </pre>
 */
@SpnBuiltin(name = "values", module = "Dict", params = {"dict"}, returns = "Array", receiver = "Dict")
@NodeChild("dict")
@NodeInfo(shortName = "values")
public abstract class SpnDictValuesNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue values(SpnDictionaryValue dict) {
        return new SpnArrayValue(dict.getValueType(), dict.values());
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("values expects a Dict, got: "
                + value.getClass().getSimpleName(), this);
    }
}
