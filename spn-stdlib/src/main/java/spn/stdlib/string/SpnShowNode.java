package spn.stdlib.string;

import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;
import spn.type.SpnSymbol;

/**
 * Universal stringification. Converts any SPN value to its string representation.
 * Referenced in the syntax spec by dict pattern matching example.
 *
 * <pre>
 *   show(42)       -> "42"
 *   show(3.14)     -> "3.14"
 *   show(true)     -> "true"
 *   show(:red)     -> ":red"
 *   show([1,2,3])  -> "[1, 2, 3]"
 * </pre>
 */
@SpnBuiltin(name = "show", module = "String", params = {"value"}, returns = "String")
@NodeChild("value")
@NodeInfo(shortName = "show")
public abstract class SpnShowNode extends SpnExpressionNode {

    @Specialization
    protected String showLong(long value) {
        return Long.toString(value);
    }

    @Specialization
    protected String showDouble(double value) {
        return Double.toString(value);
    }

    @Specialization
    protected String showBoolean(boolean value) {
        return Boolean.toString(value);
    }

    @Specialization
    protected String showString(String value) {
        return value;
    }

    @Specialization
    protected String showSymbol(SpnSymbol value) {
        return value.toString();
    }

    @Specialization
    protected String showObject(Object value) {
        return value.toString();
    }
}
