package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Heron's formula: area of a triangle given side lengths a, b, c.
 * Referenced in the syntax spec by the area() function.
 */
@SpnBuiltin(name = "heron", module = "Math", params = {"a", "b", "c"}, returns = "Double")
@NodeChild("a")
@NodeChild("b")
@NodeChild("c")
@NodeInfo(shortName = "heron")
public abstract class SpnHeronNode extends SpnExpressionNode {

    @Specialization
    protected double heron(double a, double b, double c) {
        double s = (a + b + c) / 2.0;
        return Math.sqrt(s * (s - a) * (s - b) * (s - c));
    }

    @Fallback
    protected Object typeError(Object a, Object b, Object c) {
        throw new SpnException("heron expects three numbers", this);
    }
}
