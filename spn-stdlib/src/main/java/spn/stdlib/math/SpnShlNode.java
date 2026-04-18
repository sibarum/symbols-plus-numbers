package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Left shift. Useful for computing rounding offsets like {@code shl(1, k-1)}
 * for round-to-nearest rescaling.
 *
 * <pre>
 *   shl(1, 3) -> 8
 *   shl(5, 2) -> 20
 * </pre>
 */
@SpnBuiltin(name = "shl", module = "Math", params = {"value", "count"}, returns = "Long")
@NodeChild("value")
@NodeChild("count")
@NodeInfo(shortName = "shl")
public abstract class SpnShlNode extends SpnExpressionNode {

    @Specialization
    protected long shl(long value, long count) {
        return value << count;
    }

    @Fallback
    protected Object typeError(Object value, Object count) {
        throw new SpnException("shl expects two integers", this);
    }
}
