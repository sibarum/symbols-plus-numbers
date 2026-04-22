package spn.node.array;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnArrayValue;
import spn.type.SpnDictionaryValue;
import spn.type.SpnSymbol;

/**
 * Subscript access for builtin collection values.
 *
 * <p>Dispatches at runtime:
 * <ul>
 *   <li>{@code SpnArrayValue[long]} — 0-based element lookup, bounds-checked.</li>
 *   <li>{@code SpnDictionaryValue[SpnSymbol]} — key lookup; throws on missing key.</li>
 * </ul>
 *
 * <p>User-defined types get subscripting via the {@code []} method on the
 * receiver (see {@code pure TypeName[](args) -> ret} syntax). The parser
 * dispatches those through {@code SpnMethodInvokeNode} and only falls back
 * here for builtin collections.
 *
 * <pre>
 *   arr[1]        -> SpnArrayValue.get(1)
 *   dict[:key]    -> SpnDictionaryValue.get(:key)
 * </pre>
 */
@NodeChild("array")
@NodeChild("index")
@NodeInfo(shortName = "arrayAccess")
public abstract class SpnArrayAccessNode extends SpnExpressionNode {

    @Specialization
    protected Object access(SpnArrayValue array, long index) {
        int i = (int) index;
        if (i < 0 || i >= array.length()) {
            throw new SpnException(
                    "Array index out of bounds: " + index + " (length " + array.length() + ")",
                    this);
        }
        return array.get(i);
    }

    @Specialization
    protected Object dictAccess(SpnDictionaryValue dict, SpnSymbol key) {
        Object value = dict.get(key);
        if (value == null && !dict.containsKey(key)) {
            throw new SpnException("Key :" + key.name() + " not found in dictionary", this);
        }
        return value;
    }

    @Fallback
    protected Object typeError(Object receiver, Object index) {
        if (receiver instanceof SpnArrayValue) {
            throw new SpnException("Array index must be a Long, got: "
                    + SpnTypeName.of(index), this);
        }
        if (receiver instanceof SpnDictionaryValue) {
            throw new SpnException("Dict key must be a Symbol, got: "
                    + SpnTypeName.of(index), this);
        }
        throw new SpnException("Subscript on unsupported type: "
                + SpnTypeName.of(receiver), this);
    }
}
