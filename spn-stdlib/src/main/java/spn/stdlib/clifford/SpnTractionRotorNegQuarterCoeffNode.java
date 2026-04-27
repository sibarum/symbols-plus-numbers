package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorNegQuarterCoeff", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "negQuarterCoeff")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorNegQuarterCoeff")
public abstract class SpnTractionRotorNegQuarterCoeffNode extends SpnExpressionNode {

    @Specialization
    protected double negQuarterCoeff(TractionRotor rotor) {
        return rotor.negQuarterCoeff();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("negQuarterCoeff expects a TractionRotor", this);
    }
}
