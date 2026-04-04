package spn.stdlib.string;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Converts to lowercase: toLower("HELLO") -> "hello"
 */
@SpnBuiltin(name = "toLower", module = "String", params = {"string"}, returns = "String")
@NodeChild("string")
@NodeInfo(shortName = "toLower")
public abstract class SpnStringToLowerNode extends SpnExpressionNode {

    @Specialization
    protected String toLower(String string) {
        return string.toLowerCase();
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("toLower expects a String, got: "
                + value.getClass().getSimpleName(), this);
    }
}
