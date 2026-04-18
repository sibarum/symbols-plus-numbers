package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Logical (unsigned) right shift. Zeros are shifted in from the left regardless
 * of sign, which is the correct semantics for shrinking a magnitude. For signed
 * values, separate sign and magnitude first (sign * shr(abs(n), k)).
 *
 * <pre>
 *   shr(16, 2) -> 4
 *   shr(-1, 1) -> 9223372036854775807     -- unsigned shift of all-ones
 * </pre>
 */
@SpnBuiltin(name = "shr", module = "Math", params = {"value", "count"}, returns = "Long")
@NodeChild("value")
@NodeChild("count")
@NodeInfo(shortName = "shr")
public abstract class SpnShrNode extends SpnExpressionNode {

    @Specialization
    protected long shr(long value, long count) {
        return value >>> count;
    }

    @Fallback
    protected Object typeError(Object value, Object count) {
        throw new SpnException("shr expects two integers", this);
    }
}
