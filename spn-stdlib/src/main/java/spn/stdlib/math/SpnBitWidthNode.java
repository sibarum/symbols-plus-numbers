package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Bit width of an integer — the minimum number of bits needed to represent
 * |value| in binary. Sign is ignored.
 *
 * <pre>
 *   bitWidth(0) -> 0
 *   bitWidth(1) -> 1
 *   bitWidth(7) -> 3     -- 111
 *   bitWidth(-8) -> 4    -- |-8| = 1000
 * </pre>
 */
@SpnBuiltin(name = "bitWidth", module = "Math", params = {"value"}, returns = "Long")
@NodeChild("value")
@NodeInfo(shortName = "bitWidth")
public abstract class SpnBitWidthNode extends SpnExpressionNode {

    @Specialization
    protected long bitWidth(long value) {
        if (value == Long.MIN_VALUE) return 64; // |MIN_VALUE| overflows, treat specially
        return 64L - Long.numberOfLeadingZeros(Math.abs(value));
    }

    @Fallback
    protected Object typeError(Object value) {
        throw new SpnException("bitWidth expects an integer", this);
    }
}
