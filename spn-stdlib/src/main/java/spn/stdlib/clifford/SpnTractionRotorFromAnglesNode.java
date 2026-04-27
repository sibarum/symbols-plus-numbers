package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "tractionRotor", module = "Clifford",
        params = {"thetaW", "thetaU"}, returns = "TractionRotor")
@NodeChild("thetaW")
@NodeChild("thetaU")
@NodeInfo(shortName = "tractionRotor")
public abstract class SpnTractionRotorFromAnglesNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor fromAngles(double thetaW, double thetaU) {
        return TractionRotor.fromAngles(thetaW, thetaU);
    }

    @Fallback
    protected Object typeError(Object thetaW, Object thetaU) {
        throw new SpnException("tractionRotor expects two numbers (thetaW, thetaU)", this);
    }
}
