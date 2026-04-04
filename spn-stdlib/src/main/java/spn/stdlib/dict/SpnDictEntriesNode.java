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
import spn.type.SpnSymbol;
import spn.type.SpnTupleDescriptor;
import spn.type.SpnTupleValue;

import java.util.Map;

/**
 * Returns all entries as an array of (Symbol, value) tuples.
 *
 * <pre>
 *   entries({:name "Alice"}) -> [(:name, "Alice")]
 * </pre>
 */
@SpnBuiltin(name = "entries", module = "Dict", params = {"dict"}, returns = "Array")
@NodeChild("dict")
@NodeInfo(shortName = "entries")
public abstract class SpnDictEntriesNode extends SpnExpressionNode {

    private static final SpnTupleDescriptor ENTRY_DESC =
            new SpnTupleDescriptor(FieldType.SYMBOL, FieldType.UNTYPED);

    @Specialization
    protected SpnArrayValue entries(SpnDictionaryValue dict) {
        Map<SpnSymbol, Object> map = dict.getEntries();
        Object[] result = new Object[map.size()];
        int i = 0;
        for (var entry : map.entrySet()) {
            result[i++] = new SpnTupleValue(ENTRY_DESC, entry.getKey(), entry.getValue());
        }
        return new SpnArrayValue(FieldType.UNTYPED, result);
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("entries expects a Dict, got: "
                + value.getClass().getSimpleName(), this);
    }
}
