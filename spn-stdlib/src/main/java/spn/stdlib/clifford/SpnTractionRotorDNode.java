package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorD", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "d")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorD")
public abstract class SpnTractionRotorDNode extends SpnExpressionNode {

    @Specialization
    protected double d(TractionRotor rotor) {
        return rotor.d();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("d expects a TractionRotor", this);
    }
}
