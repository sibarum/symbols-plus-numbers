package spn.stdlib.clifford;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.clifford.TractionRotor;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

@SpnBuiltin(name = "rotorA", module = "Clifford",
        params = {"rotor"}, returns = "Double",
        receiver = "TractionRotor", method = "a")
@NodeChild("rotor")
@NodeInfo(shortName = "rotorA")
public abstract class SpnTractionRotorANode extends SpnExpressionNode {

    @Specialization
    protected double a(TractionRotor rotor) {
        return rotor.a();
    }

    @Fallback
    protected Object typeError(Object rotor) {
        throw new SpnException("a expects a TractionRotor", this);
    }
}
