package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Exponentiation: pow(2, 10) -> 1024.0
 */
@SpnBuiltin(name = "pow", module = "Math", params = {"base", "exponent"}, returns = "Double")
@NodeChild("base")
@NodeChild("exponent")
@NodeInfo(shortName = "pow")
public abstract class SpnPowNode extends SpnExpressionNode {

    @Specialization
    protected double powDoubles(double base, double exponent) {
        return Math.pow(base, exponent);
    }

    @Fallback
    protected Object typeError(Object base, Object exponent) {
        throw new SpnException("pow expects two numbers", this);
    }
}
