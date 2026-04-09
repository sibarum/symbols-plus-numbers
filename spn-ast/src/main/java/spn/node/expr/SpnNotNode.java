package spn.node.expr;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Unary boolean NOT.
 */
@NodeChild("value")
@NodeInfo(shortName = "!")
public abstract class SpnNotNode extends SpnExpressionNode {

    @Specialization
    protected boolean notBoolean(boolean value) {
        return !value;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("Type error: !(" + SpnTypeName.of(value)
                + ") is not defined", this);
    }
}
