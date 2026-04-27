package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorNormSquared", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "normSquared")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorNormSquared")
public abstract class SpnTractionRotorNormSquaredNode extends SpnExpressionNode {

    @Specialization
    protected double normSquared(TractionRotor rotor) {
        return rotor.normSquared();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("normSquared expects a TractionRotor", this);
    }
}
