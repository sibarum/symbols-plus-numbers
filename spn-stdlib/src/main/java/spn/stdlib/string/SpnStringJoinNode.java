package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnArrayValue;

/**
 * Joins an array of strings with a delimiter: join(["a", "b", "c"], ",") -> "a,b,c"
 */
@SpnBuiltin(name = "join", module = "String", params = {"array", "delimiter"}, returns = "String")
@NodeChild("array")
@NodeChild("delimiter")
@NodeInfo(shortName = "join")
public abstract class SpnStringJoinNode extends SpnExpressionNode {

    @Specialization
    protected String join(SpnArrayValue array, String delimiter) {
        Object[] elements = array.getElements();
        var sb = new StringBuilder();
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(elements[i]);
        }
        return sb.toString();
    }

    @Fallback
    protected Object typeError(Object array, Object delimiter) {
        throw new SpnException("join expects (Array, String)", this);
    }
}
