package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorScalarCoeff", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "scalarCoeff")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorScalarCoeff")
public abstract class SpnTractionRotorScalarCoeffNode extends SpnExpressionNode {

    @Specialization
    protected double scalarCoeff(TractionRotor rotor) {
        return rotor.scalarCoeff();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("scalarCoeff expects a TractionRotor", this);
    }
}
