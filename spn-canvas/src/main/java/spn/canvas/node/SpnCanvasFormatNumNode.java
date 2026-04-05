package spn.canvas.node;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;

/**
 * Formats a number as a short string suitable for axis labels.
 * Integers render without decimals. Doubles render with up to 2 decimal places.
 */
@NodeChild("value")
@NodeInfo(shortName = "formatNum")
public abstract class SpnCanvasFormatNumNode extends SpnExpressionNode {

    @Specialization
    protected String formatLong(long value) {
        return Long.toString(value);
    }

    @Specialization
    protected String formatDouble(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        // Up to 2 decimal places, strip trailing zeros
        String s = String.format("%.2f", value);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            s = s.replaceAll("\\.$", "");
        }
        return s;
    }
}
