package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Tests if a string starts with a prefix: startsWith("hello", "he") -> true
 */
@SpnBuiltin(name = "startsWith", module = "String", params = {"string", "prefix"}, returns = "Boolean")
@NodeChild("string")
@NodeChild("prefix")
@NodeInfo(shortName = "startsWith")
public abstract class SpnStringStartsWithNode extends SpnExpressionNode {

    @Specialization
    protected boolean startsWith(String string, String prefix) {
        return string.startsWith(prefix);
    }

    @Fallback
    protected Object typeError(Object string, Object prefix) {
        throw new SpnException("startsWith expects (String, String)", this);
    }
}
