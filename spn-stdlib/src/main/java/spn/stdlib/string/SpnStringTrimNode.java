package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Removes leading and trailing whitespace: trim("  hello  ") -> "hello"
 */
@SpnBuiltin(name = "trim", module = "String", params = {"string"}, returns = "String")
@NodeChild("string")
@NodeInfo(shortName = "trim")
public abstract class SpnStringTrimNode extends SpnExpressionNode {

    @Specialization
    protected String trim(String string) {
        return string.strip();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("trim expects a String, got: "
                + value.getClass().getSimpleName(), this);
    }
}
