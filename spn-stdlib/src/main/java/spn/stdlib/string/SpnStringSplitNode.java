package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.FieldType;
import spn.type.SpnArrayValue;

/**
 * Splits a string by a delimiter: split("a,b,c", ",") -> ["a", "b", "c"]
 */
@SpnBuiltin(name = "split", module = "String", params = {"string", "delimiter"}, returns = "Array")
@NodeChild("string")
@NodeChild("delimiter")
@NodeInfo(shortName = "split")
public abstract class SpnStringSplitNode extends SpnExpressionNode {

    @Specialization
    protected SpnArrayValue split(String string, String delimiter) {
        String[] parts = string.split(java.util.regex.Pattern.quote(delimiter), -1);
        Object[] elements = new Object[parts.length];
        System.arraycopy(parts, 0, elements, 0, parts.length);
        return new SpnArrayValue(FieldType.STRING, elements);
    }

    @Fallback
    protected Object typeError(Object string, Object delimiter) {
        throw new SpnException("split expects (String, String)", this);
    }
}
