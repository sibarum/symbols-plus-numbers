package spn.node.expr;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Unary negation. Demonstrates @NodeChild with a single child.
 *
 * With a single @NodeChild("value"), the DSL generates code that executes the child
 * and passes its result to the matching @Specialization method.
 */
@NodeChild("value")
@NodeInfo(shortName = "-")
public abstract class SpnNegateNode extends SpnExpressionNode {

    @Specialization(rewriteOn = ArithmeticException.class)
    protected long negateLong(long value) {
        return Math.negateExact(value);
    }

    @Specialization(replaces = "negateLong")
    protected double negateDouble(double value) {
        return -value;
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("Type error: -(" + SpnTypeName.of(value)
                + ") is not defined", this);
    }
}
