package spn.stdlib.string;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Formats a number as a short string suitable for display/labels.
 * Integers render without decimals. Doubles render with up to 2 decimal places.
 */
@SpnBuiltin(name = "formatNum", module = "String", returns = "String")
@NodeChild("value")
@NodeInfo(shortName = "formatNum")
public abstract class SpnFormatNumNode extends SpnExpressionNode {

    @Specialization
    protected String formatLong(long value) {
        return Long.toString(value);
    }

    @Specialization
    protected String formatDouble(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        String s = String.format("%.2f", value);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            s = s.replaceAll("\\.$", "");
        }
        return s;
    }
}
