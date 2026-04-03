package spn.node.set;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.type.SpnSetValue;

/**
 * Binary set operations: union, intersection, difference. All return new immutable sets.
 *
 * <pre>
 *   SpnSetBinaryOpNodeGen.create(readA, readB, SetOp.UNION)
 * </pre>
 */
@NodeChild("left")
@NodeChild("right")
@NodeField(name = "op", type = SpnSetBinaryOpNode.SetOp.class)
@NodeInfo(shortName = "setOp")
public abstract class SpnSetBinaryOpNode extends SpnExpressionNode {

    public enum SetOp {
        UNION, INTERSECTION, DIFFERENCE
    }

    protected abstract SetOp getOp();

    @Specialization
    protected SpnSetValue operate(SpnSetValue left, SpnSetValue right) {
        return switch (getOp()) {
            case UNION -> left.union(right);
            case INTERSECTION -> left.intersection(right);
            case DIFFERENCE -> left.difference(right);
        };
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Set operation requires two sets", this);
    }
}
