package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorC", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "c")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorC")
public abstract class SpnTractionRotorCNode extends SpnExpressionNode {

    @Specialization
    protected double c(TractionRotor rotor) {
        return rotor.c();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("c expects a TractionRotor", this);
    }
}
