package spn.node.expr;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * Inequality comparison. Supports longs, doubles, booleans, and strings,
 * with a generic fallback using Object.equals.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "!=")
public abstract class SpnNotEqualNode extends SpnExpressionNode {

    @Specialization
    protected boolean notEqualLongs(long left, long right) {
        return left != right;
    }

    @Specialization
    protected boolean notEqualDoubles(double left, double right) {
        return left != right;
    }

    @Specialization
    protected boolean notEqualBooleans(boolean left, boolean right) {
        return left != right;
    }

    @Specialization
    protected boolean notEqualStrings(String left, String right) {
        return !left.equals(right);
    }

    @Fallback
    protected boolean notEqualGeneric(Object left, Object right) {
        return !left.equals(right);
    }
}
