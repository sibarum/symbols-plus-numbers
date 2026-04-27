package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorNorm", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "norm")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorNorm")
public abstract class SpnTractionRotorNormNode extends SpnExpressionNode {

    @Specialization
    protected double norm(TractionRotor rotor) {
        return rotor.norm();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("norm expects a TractionRotor", this);
    }
}
