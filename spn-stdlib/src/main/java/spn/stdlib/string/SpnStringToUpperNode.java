package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Converts to uppercase: toUpper("hello") -> "HELLO"
 */
@SpnBuiltin(name = "toUpper", module = "String", params = {"string"}, returns = "String")
@NodeChild("string")
@NodeInfo(shortName = "toUpper")
public abstract class SpnStringToUpperNode extends SpnExpressionNode {

    @Specialization
    protected String toUpper(String string) {
        return string.toUpperCase();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("toUpper expects a String, got: "
                + value.getClass().getSimpleName(), this);
    }
}
