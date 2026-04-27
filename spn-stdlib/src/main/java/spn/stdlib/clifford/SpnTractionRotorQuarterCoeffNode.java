package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorQuarterCoeff", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "quarterCoeff")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorQuarterCoeff")
public abstract class SpnTractionRotorQuarterCoeffNode extends SpnExpressionNode {

    @Specialization
    protected double quarterCoeff(TractionRotor rotor) {
        return rotor.quarterCoeff();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("quarterCoeff expects a TractionRotor", this);
    }
}
