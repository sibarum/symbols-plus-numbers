package spn.stdlib.dict;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.node.builtin.SpnParamHint;
import spn.type.FieldType;
import spn.type.SpnDictionaryValue;
import spn.type.SpnSymbol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transforms all values in a dictionary by applying a function, preserving keys.
 *
 * <pre>
 *   mapValues({:a 1, :b 2}, double) -> {:a 2, :b 4}
 * </pre>
 */
@SpnBuiltin(name = "mapValues", module = "Dict", params = {"dict"}, returns = "Dict")
@SpnParamHint(name = "function", function = true)
@NodeChild("dict")
@NodeInfo(shortName = "mapValues")
public abstract class SpnDictMapValuesNode extends SpnExpressionNode {

    @Child private DirectCallNode callNode;

    protected SpnDictMapValuesNode(CallTarget function) {
        this.callNode = Truffle.getRuntime().createDirectCallNode(function);
    }

    @Specialization
    protected SpnDictionaryValue mapValues(SpnDictionaryValue dict) {
        Map<SpnSymbol, Object> entries = dict.getEntries();
        var result = new LinkedHashMap<SpnSymbol, Object>();
        for (var entry : entries.entrySet()) {
            result.put(entry.getKey(), callNode.call(entry.getValue()));
        }
        return SpnDictionaryValue.wrap(FieldType.UNTYPED, result);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("mapValues expects a Dict, got: "
                + value.getClass().getSimpleName(), this);
    }
}
