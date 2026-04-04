package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Replaces all occurrences: replace("aabaa", "a", "x") -> "xxbxx"
 */
@SpnBuiltin(name = "replace", module = "String", params = {"string", "from", "to"}, returns = "String")
@NodeChild("string")
@NodeChild("from")
@NodeChild("to")
@NodeInfo(shortName = "replace")
public abstract class SpnStringReplaceNode extends SpnExpressionNode {

    @Specialization
    protected String replace(String string, String from, String to) {
        return string.replace(from, to);
    }

    @Fallback
    protected Object typeError(Object string, Object from, Object to) {
        throw new SpnException("replace expects (String, String, String)", this);
    }
}
