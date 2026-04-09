package spn.node.expr;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Greater-than comparison. Returns a boolean.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = ">")
public abstract class SpnGreaterThanNode extends SpnExpressionNode {

    @Specialization
    protected boolean greaterThanLongs(long left, long right) {
        return left > right;
    }

    @Specialization
    protected boolean greaterThanDoubles(double left, double right) {
        return left > right;
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Cannot compare " + SpnTypeName.of(left)
                + " and " + SpnTypeName.of(right), this);
    }
}
