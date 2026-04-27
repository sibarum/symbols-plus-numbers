package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorNormalize", module = "Clifford",
        params = {"rotor"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "normalize")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorNormalize")
public abstract class SpnTractionRotorNormalizeNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor normalize(TractionRotor rotor) {
        return rotor.normalize();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("normalize expects a TractionRotor", this);
    }
}
