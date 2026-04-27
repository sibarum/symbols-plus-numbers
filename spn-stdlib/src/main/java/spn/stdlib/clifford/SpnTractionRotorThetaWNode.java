package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorThetaW", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "thetaW")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorThetaW")
public abstract class SpnTractionRotorThetaWNode extends SpnExpressionNode {

    @Specialization
    protected double thetaW(TractionRotor rotor) {
        return rotor.thetaW();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("thetaW expects a TractionRotor", this);
    }
}
