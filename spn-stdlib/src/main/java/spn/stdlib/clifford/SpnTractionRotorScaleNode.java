package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorScale", module = "Clifford",
        params = {"rotor", "k"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "scale")
@NodeChild("rotor")
@NodeChild("k")
@NodeInfo(shortName = "rotorScale")
public abstract class SpnTractionRotorScaleNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor scale(TractionRotor rotor, double k) {
        return rotor.scale(k);
    }

    @Fallback
    protected Object typeError(Object rotor, Object k) {
        throw new SpnException("scale expects (TractionRotor, Double)", this);
    }
}
