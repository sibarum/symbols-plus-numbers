package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorInverse", module = "Clifford",
        params = {"rotor"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "inverse")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorInverse")
public abstract class SpnTractionRotorInverseNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor inverse(TractionRotor rotor) {
        return rotor.inverse();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("inverse expects a TractionRotor", this);
    }
}
