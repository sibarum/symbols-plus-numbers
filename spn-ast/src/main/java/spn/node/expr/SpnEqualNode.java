package spn.node.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * Equality comparison. Supports longs, doubles, booleans, and strings,
 * with a generic fallback using Object.equals.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "==")
public abstract class SpnEqualNode extends SpnExpressionNode {

    @Specialization
    protected boolean equalLongs(long left, long right) {
        return left == right;
    }

    @Specialization
    protected boolean equalDoubles(double left, double right) {
        return left == right;
    }

    @Specialization
    protected boolean equalBooleans(boolean left, boolean right) {
        return left == right;
    }

    @Specialization
    protected boolean equalStrings(String left, String right) {
        return left.equals(right);
    }

    @Fallback
    protected boolean equalGeneric(Object left, Object right) {
        return left.equals(right);
    }
}
