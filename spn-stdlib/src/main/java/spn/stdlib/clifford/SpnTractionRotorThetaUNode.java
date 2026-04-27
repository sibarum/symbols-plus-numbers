package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorThetaU", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "thetaU")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorThetaU")
public abstract class SpnTractionRotorThetaUNode extends SpnExpressionNode {

    @Specialization
    protected double thetaU(TractionRotor rotor) {
        return rotor.thetaU();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("thetaU expects a TractionRotor", this);
    }
}
