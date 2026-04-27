package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorInterpAngles", module = "Clifford",
        params = {"rotor", "other", "t"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "interpAngles")
@NodeChild("rotor")
@NodeChild("other")
@NodeChild("t")
@NodeInfo(shortName = "rotorInterpAngles")
public abstract class SpnTractionRotorInterpAnglesNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor interpAngles(TractionRotor rotor, TractionRotor other, double t) {
        return rotor.interpAngles(other, t);
    }

    @Fallback
    protected Object typeError(Object rotor, Object other, Object t) {
        throw new SpnException("interpAngles expects (TractionRotor, TractionRotor, Double)", this);
    }
}
