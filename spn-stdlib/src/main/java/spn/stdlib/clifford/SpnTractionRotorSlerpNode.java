package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorSlerp", module = "Clifford",
        params = {"rotor", "other", "t"}, returns = "TractionRotor",
        receiver = "TractionRotor", method = "slerp")
@NodeChild("rotor")
@NodeChild("other")
@NodeChild("t")
@NodeInfo(shortName = "rotorSlerp")
public abstract class SpnTractionRotorSlerpNode extends SpnExpressionNode {

    @Specialization
    protected TractionRotor slerp(TractionRotor rotor, TractionRotor other, double t) {
        return rotor.slerp(other, t);
    }

    @Fallback
    protected Object typeError(Object rotor, Object other, Object t) {
        throw new SpnException("slerp expects (TractionRotor, TractionRotor, Double)", this);
    }
}
