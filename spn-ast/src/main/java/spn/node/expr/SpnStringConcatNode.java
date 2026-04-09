package spn.node.expr;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * String concatenation operator (++). Only operates on strings.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "++")
public abstract class SpnStringConcatNode extends SpnExpressionNode {

    @Specialization
    protected String concat(String left, String right) {
        return left + right;
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Type error: ++(" + SpnTypeName.of(left)
                + ", " + SpnTypeName.of(right) + ") is not defined", this);
    }
}
