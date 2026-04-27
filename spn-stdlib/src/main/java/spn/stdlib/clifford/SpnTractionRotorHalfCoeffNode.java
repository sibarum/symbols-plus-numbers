package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorHalfCoeff", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "halfCoeff")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorHalfCoeff")
public abstract class SpnTractionRotorHalfCoeffNode extends SpnExpressionNode {

    @Specialization
    protected double halfCoeff(TractionRotor rotor) {
        return rotor.halfCoeff();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("halfCoeff expects a TractionRotor", this);
    }
}
